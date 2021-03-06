/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.validation.flows

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Zip }
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.{ AlgorithmExecutor, AlgorithmResults }
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.fp.runLater
import ch.chuv.lren.woken.core.streams.debugElements
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import ch.chuv.lren.woken.service.FeaturesTableService
import ch.chuv.lren.woken.validation.FeaturesSplitter
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.control.NonFatal

object AlgorithmWithCVFlow {

  case class Job[F[_]](jobId: String,
                       algorithm: AlgorithmSpec,
                       algorithmDefinition: AlgorithmDefinition,
                       featuresTableService: FeaturesTableService[F],
                       query: FeaturesQuery,
                       cvSplitters: List[FeaturesSplitter[F]],
                       metadata: List[VariableMetaData]) {
    // Invariants
    assert(
      query.dbTable == featuresTableService.table.table,
      s"Expected query table ${query.dbTable} to match service table ${featuresTableService.table.table}"
    )
    assert(
      algorithm.code == algorithmDefinition.code,
      s"Expected algorithm specification ${algorithm.code} to match algorithm definition ${algorithmDefinition.code}"
    )

    if (!algorithmDefinition.predictive) {
      assert(cvSplitters.isEmpty)
    }
  }

  type ValidationResults = Map[ValidationSpec, Either[String, Score]]

  case class ResultResponse(algorithm: AlgorithmSpec, model: Either[ErrorJobResult, PfaJobResult])

}

/**
  * Generates flows for execution of an algorithm that may require Cross Validation of the model built during its training phase.
  *
  * @tparam F Monadic effect
  */
case class AlgorithmWithCVFlow[F[_]: Effect](
    algorithmExecutor: AlgorithmExecutor[F],
    wokenWorker: WokenWorker[F]
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import AlgorithmWithCVFlow._

  private val crossValidationFlow =
    CrossValidationFlow(algorithmExecutor, wokenWorker)

  /**
    * Run a local predictive algorithm and benchmark its model using the cross-validation procedure on local data.
    *
    * @param parallelism Parallelism factor
    * @return A flow that executes an algorithm and its validation procedures
    */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def runLocalAlgorithmAndCrossValidate(
      parallelism: Int
  ): Flow[AlgorithmWithCVFlow.Job[F], ResultResponse, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val broadcast = builder.add(Broadcast[AlgorithmWithCVFlow.Job[F]](outputPorts = 2))
        val zip       = builder.add(Zip[AlgorithmResults, ValidationResults]())
        val response  = builder.add(buildResponse)

        // connect the graph
        broadcast.out(0) ~> runAlgorithmOnLocalData.map(_._2) ~> zip.in0
        broadcast.out(1) ~> crossValidate(parallelism) ~> zip.in1
        zip.out ~> response

        FlowShape(broadcast.in, response.out)
      })
      .named("run-algorithm-and-cross-validate")

  /**
    * Execute an algorithm and learn from the local data.
    *
    * @return
    */
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def runAlgorithmOnLocalData
    : Flow[AlgorithmWithCVFlow.Job[F], (AlgorithmWithCVFlow.Job[F], AlgorithmResults), NotUsed] =
    Flow[AlgorithmWithCVFlow.Job[F]]
      .named("learn-from-available-local-data")
      .mapAsync(1) { job =>
        val algorithm = job.algorithm

        logger.info(s"Start job for algorithm ${algorithm.code}")

        // Spawn a CoordinatorActor
        val subJobId = UUID.randomUUID().toString
        val subJob =
          DockerJob(subJobId, job.query, job.algorithm, job.algorithmDefinition, job.metadata)

        val errorRecovery: Throwable => F[(AlgorithmWithCVFlow.Job[F], AlgorithmResults)] = {
          case NonFatal(t) =>
            val errorResult = ErrorJobResult(Some(subJobId),
                                             algorithmExecutor.node,
                                             OffsetDateTime.now(),
                                             Some(subJob.algorithmDefinition.code),
                                             t.getMessage)
            (job, AlgorithmResults(subJob, List(errorResult))).pure[F]
          case fatal => Effect[F].raiseError(fatal)
        }

        runLater(algorithmExecutor.execute(subJob).map { response: AlgorithmResults =>
          (job, response)
        }, errorRecovery)
      }
      .log("Learned from available local data")
      .withAttributes(debugElements)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def crossValidate(
      parallelism: Int
  ): Flow[AlgorithmWithCVFlow.Job[F], ValidationResults, NotUsed] =
    Flow[AlgorithmWithCVFlow.Job[F]]
      .named("cross-validate")
      .map { job =>
        job.cvSplitters.map { splitter =>
          val jobId          = UUID.randomUUID().toString
          val inputTableDesc = job.featuresTableService.table
          val orderBy = inputTableDesc.primaryKey match {
            case pk1 :: Nil => Some(pk1.name)
            case _          => None
          }
          val query = job.query.copy(orderBy = orderBy)
          createCrossValidationJob(job, splitter, jobId, query)
        }
      }
      .mapConcat(identity)
      .via(crossValidationFlow.crossValidate(parallelism))
      .map(_.map(t => t._1.splitter.definition -> t._2))
      .fold[Map[ValidationSpec, Either[String, Score]]](Map()) { (m, rOpt) =>
        rOpt.fold(m) { r =>
          m + (r._1.validation -> r._2)
        }
      }
      .log("Cross validation results")
      .withAttributes(debugElements)

  private def createCrossValidationJob(job: AlgorithmWithCVFlow.Job[F],
                                       splitter: FeaturesSplitter[F],
                                       jobId: String,
                                       query: FeaturesQuery) =
    CrossValidationFlow.Job(jobId,
                            query,
                            job.metadata,
                            splitter,
                            job.featuresTableService,
                            job.algorithm,
                            job.algorithmDefinition)

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private def buildResponse: Flow[(AlgorithmResults, ValidationResults), ResultResponse, NotUsed] =
    Flow[(AlgorithmResults, ValidationResults)]
      .named("build-response")
      .map {
        case (response, validations) =>
          val algorithm = response.job.algorithmSpec
          response.results.headOption match {
            case Some(pfa: PfaJobResult) =>
              val model = pfa.copy(validations = pfa.validations ++ validations)
              ResultResponse(algorithm, Right(model))
            case Some(errJobResult: ErrorJobResult) =>
              ResultResponse(algorithm, Left(errJobResult))
            case Some(model) =>
              logger.warn(
                s"Expected a PfaJobResult, got $model. All results and validations are discarded"
              )
              val jobResult =
                ErrorJobResult(
                  Some(response.job.jobId),
                  node = algorithmExecutor.node,
                  OffsetDateTime.now(),
                  Some(algorithm.code),
                  s"Expected a PfaJobResult, got ${model.getClass.getName}"
                )
              ResultResponse(algorithm, Left(jobResult))
            case None =>
              val jobResult = ErrorJobResult(Some(response.job.jobId),
                                             node = algorithmExecutor.node,
                                             OffsetDateTime.now(),
                                             Some(algorithm.code),
                                             "No results")
              ResultResponse(algorithm, Left(jobResult))
          }
      }
      .log("Response")
      .withAttributes(debugElements)
}
