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

package ch.chuv.lren.woken.dispatch

import akka.NotUsed
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ Actor, ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.effect.{ Effect, Sync }
import cats.implicits._
import ch.chuv.lren.woken.core.fp._
import ch.chuv.lren.woken.core.streams.debugElements
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.errors._
import ch.chuv.lren.woken.validation.flows.RemoteValidationFlow.ValidationContext
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.query.queryProtocol._
import ch.chuv.lren.woken.mining.LocalExperimentService
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.validation.flows.RemoteValidationFlow
import com.typesafe.scalalogging.LazyLogging
import spray.json._

import scala.collection.immutable.TreeSet
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }

object ExperimentQueriesActor extends LazyLogging {

  case class Experiment(query: ExperimentQuery, replyTo: ActorRef)

  def props[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F]
  ): Props =
    Props(new ExperimentQueriesActor(config, databaseServices, backendServices))

  def roundRobinPoolProps[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F]
  ): Props = {

    val resizer = OptimalSizeExploringResizer(
      config.config
        .getConfig("poolResizer.experimentQueries")
        .withFallback(
          config.config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val experimentSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case e: Exception =>
          logger.error("Error detected in Experiment queries actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = experimentSupervisorStrategy
    ).props(
      ExperimentQueriesActor.props(config, databaseServices, backendServices)
    )
  }

}

class ExperimentQueriesActor[F[_]: Effect](
    override val config: WokenConfiguration,
    override val databaseServices: DatabaseServices[F],
    override val backendServices: BackendServices[F]
) extends QueriesActor[ExperimentQuery, F] {

  import ExperimentQueriesActor.Experiment

  val localExperimentService = LocalExperimentService(
    backendServices.algorithmExecutor,
    backendServices.wokenWorker,
    databaseServices.featuresService,
    databaseServices.wokenRepository,
    context.system
  )

  val remoteValidationFlow: Flow[ValidationContext, ValidationContext, NotUsed] =
    RemoteValidationFlow(dispatcherService, context).remoteValidate

  override def receive: Receive = {

    case experiment: Experiment =>
      logger.debug("Received experiment")
      val initiator     = if (experiment.replyTo == Actor.noSender) sender() else experiment.replyTo
      val query         = experiment.query
      val jobValidatedF = queryToJobService.experimentQuery2Job(query)
      val doIt: F[QueryResult] = jobValidatedF.flatMap { jv =>
        jv.fold(
          errList =>
            Sync[F].delay {
              val errors = errList.mkString_("", ", ", "")
              val msg    = s"Experiment for $query failed with error: $errors"
              logger.error(msg)
              errorMsgResult(query, msg, Set(), List())
          },
          j => processJob(query, j)
        )
      }

      runNowAndHandle(doIt) {
        case Left(e) =>
          val msg = s"Experiment for $query failed with error: ${e.toString}"
          logger.error(SKIP_REPORTING_MARKER, msg, e)
          val result = errorMsgResult(query, msg, Set(), List())
          backendServices.errorReporter.report(e, QueryError(result))
          initiator ! result
        case Right(results) =>
          results.error.foreach { error =>
            val msg = s"Experiment for $query failed with error: $error"
            logger.error(SKIP_REPORTING_MARKER, msg)
            backendServices.errorReporter.report(new Exception(msg), QueryError(results))
          }
          logger.whenDebugEnabled {
            val feedbackMsg =
              if (results.feedback.nonEmpty) "with feedback " + results.feedback.mkString(", ")
              else ""
            results.data.foreach(_ => logger.debug(s"Experiment for $query complete $feedbackMsg"))
          }
          initiator ! results
      }

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def processJob(query: ExperimentQuery,
                         jobInProgress: ExperimentJobInProgress): F[QueryResult] = {
    logger.debug("Start processing experiment")

    val job      = jobInProgress.job
    val feedback = jobInProgress.feedback
    val prov     = jobInProgress.dataProvenance

    if (feedback.nonEmpty)
      logger.info(s"Feedback: ${feedback.mkString(", ")}")

    dispatcherService.dispatchTo(query.trainingDatasets) match {

      // Local execution of the experiment on a worker node or a standalone node
      case (_, true) =>
        logger.whenDebugEnabled(
          logger.debug(s"Local experiment for query ${query.toJson.compactPrint}")
        )
        startLocalExperimentJob(jobInProgress)

      // Offload execution of the experiment from the central server to a remote worker node
      case (remoteLocations, false) if remoteLocations.size == 1 =>
        logger.whenDebugEnabled(
          logger.debug(
            s"Remote experiment on a single node $remoteLocations for query ${query.toJson.compactPrint}"
          )
        )
        mapFlow(job.query, job.queryAlgorithms, prov, feedback)
          .mapAsync(parallelism = 1) {
            case List()        => Future(noResult(job.query, Set(), feedback))
            case List(result)  => Future(result.copy(query = query))
            case listOfResults => gatherAndReduce(query, listOfResults, None)
          }
          .log("Result of experiment")
          .withAttributes(debugElements)
          .runWith(Sink.last)
          .fromFuture

      // Dispatch the experiment from the central server to all remote worker nodes
      // TODO: support also mixing local execution with remote executions
      case (remoteLocations, _) =>
        logger.whenDebugEnabled(
          logger.debug(
            s"Remote experiment on nodes $remoteLocations for query ${query.toJson.compactPrint}"
          )
        )
        val queriesByStepExecution: Map[ExecutionStyle.Value, ExperimentQuery] =
          job.queryAlgorithms
            .flatMap { algorithm =>
              val algorithmSpec       = algorithm._1
              val algorithmDefinition = algorithm._2

              algorithmDefinition.distributedExecutionPlan.steps.map { step =>
                (step, algorithmSpec.copy(step = Some(step)))
              }.toMap
            }
            .groupBy(_._1.execution)
            .map {
              case (step, algorithmSpecs) =>
                (step,
                 job.query.copy(covariablesMustExist = true,
                                algorithms = algorithmSpecs.values.toList))
            }

        val mapQuery = queriesByStepExecution.getOrElse(ExecutionStyle.map, job.query)
        val reduceQuery = queriesByStepExecution
          .get(ExecutionStyle.reduce)
          .map(_.copy(trainingDatasets = TreeSet(), validationDatasets = TreeSet()))

        mapFlow(mapQuery, job.queryAlgorithms, prov, feedback)
          .mapAsync(parallelism = 1) {
            case List()       => Future(noResult(query, Set(), feedback))
            case List(result) => Future(result.copy(query = query))
            case mapResults =>
              gatherAndReduce(query, mapResults, reduceQuery)
          }
          .log("Result of experiment")
          .withAttributes(debugElements)
          .runWith(Sink.last)
          .fromFuture
    }
  }

  private def mapFlow(mapQuery: ExperimentQuery,
                      algorithms: Map[AlgorithmSpec, AlgorithmDefinition],
                      prov: DataProvenance,
                      feedback: UserFeedbacks): Source[List[QueryResult], NotUsed] =
    Source
      .single(mapQuery)
      .via(dispatcherService.dispatchRemoteExperimentFlow)
      .mapAsync(parallelism = 10) { result =>
        JobResult.fromQueryResult(result._2) match {
          case experimentResult: ExperimentJobResult =>
            Source
              .single(ValidationContext(mapQuery, algorithms, experimentResult, prov, feedback))
              .named("remote-validation-of-distributed-experiment")
              .via(remoteValidationFlow)
              .map[QueryResult] { r =>
                r.experimentResult.asQueryResult(mapQuery,
                                                 prov ++ r.dataProvenance,
                                                 feedback ++ r.feedback)
              }
              .recoverWithRetries[QueryResult](
                1, {
                  case e =>
                    logger.error("Cannot perform remote validation", e)
                    Source.single(
                      result._2.copy(
                        feedback = result._2.feedback :+ UserWarning(
                          s"Remote validation failed : ${e.getMessage}"
                        )
                      )
                    )
                }
              )
              .log("Result of experiment")
              .withAttributes(debugElements)
              .runWith(Sink.last)
          case r =>
            logger.warn(s"Expected an ExperimentJobResult, found $r")
            Future(result._2)
        }
      }
      .fold(List[QueryResult]()) {
        _ :+ _
      }

  private def startLocalExperimentJob(job: ExperimentJobInProgress): F[QueryResult] =
    localExperimentService
      .runExperiment(job)
      .flatMap { r =>
        r.result match {
          case Right(experimentJobResult) =>
            val vc = ValidationContext(r.jobInProgress.job.query,
                                       r.jobInProgress.job.queryAlgorithms,
                                       experimentJobResult,
                                       r.jobInProgress.dataProvenance,
                                       r.jobInProgress.feedback)
            // TODO: experiment flow should return a result + warnings when the remote validation failed partially or totally
            Source
              .single(vc)
              .named("remote-validation-of-local-experiment")
              .via(remoteValidationFlow)
              .map[QueryResult] { r =>
                r.experimentResult.asQueryResult(r.query, r.dataProvenance, r.feedback)
              }
              .log("Final result of experiment")
              .withAttributes(debugElements)
              .runWith(Sink.last)
              .fromFuture

          case _ => r.toQueryResult.pure[F]
        }

      }

  private[dispatch] def reduceUsingJobs(query: ExperimentQuery,
                                        jobIds: List[String]): ExperimentQuery =
    query.copy(algorithms = query.algorithms.map(algorithm => addJobIds(algorithm, jobIds)))

  override private[dispatch] def wrap(query: ExperimentQuery, initiator: ActorRef) =
    Experiment(query, initiator)

}
