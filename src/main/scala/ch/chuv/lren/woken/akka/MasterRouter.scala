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

package ch.chuv.lren.woken.akka

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ Actor, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.ActorMaterializer
import cats.effect.Effect
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.dispatch.{
  DispatchActors,
  ExperimentQueriesActor,
  MetadataQueriesActor,
  MiningQueriesActor
}
import ch.chuv.lren.woken.messages.{ Ping, Pong }
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse, TableId }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables._
import ch.chuv.lren.woken.service._
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.concurrent.duration._

object MasterRouter extends LazyLogging {

  def props[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F],
      dispatchActors: DispatchActors
  ): Props =
    Props(
      new MasterRouter(config, databaseServices, backendServices, dispatchActors)
    )

  def roundRobinPoolProps[F[_]: Effect](
      config: WokenConfiguration,
      databaseServices: DatabaseServices[F],
      backendServices: BackendServices[F],
      dispatchActors: DispatchActors
  ): Props = {

    val resizer = OptimalSizeExploringResizer(
      config.config
        .getConfig("poolResizer.entryPoint")
        .withFallback(
          config.config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val masterSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
        case e: Exception =>
          logger.error("Error detected in Master router actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = masterSupervisorStrategy
    ).props(
      props(config, databaseServices, backendServices, dispatchActors)
    )
  }

}

class MasterRouter[F[_]: Effect](
    val config: WokenConfiguration,
    val databaseServices: DatabaseServices[F],
    val backendServices: BackendServices[F],
    val dispatchActors: DispatchActors
) extends Actor
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def receive: Receive = {

    // For health checks in the cluster
    case Ping(role) if role.isEmpty || role.contains("woken") =>
      logger.info("Ping received")
      sender() ! Pong(Set("woken"))

    case MethodsQuery =>
      mark("MethodsQueryRequestReceived")
      sender ! MethodsResponse(databaseServices.algorithmLibraryService.algorithms)

    case ds: DatasetsQuery =>
      mark("DatasetsQueryRequestReceived")
      val allDatasets = databaseServices.datasetService.datasets().values.toSet
      val table: TableId = ds.table
        .map(t => TableId(config.jobs.defaultFeaturesDatabase.code, t))
        .getOrElse(config.jobs.defaultFeaturesTable)
      val datasets =
        if (table.name == "*") allDatasets
        else allDatasets.filter(_.tables.contains(table))

      sender ! DatasetsResponse(datasets.map(_.withoutAuthenticationDetails))

    case query: VariablesForDatasetsQuery =>
      dispatchActors.metadataQueriesWorker forward MetadataQueriesActor.VariablesForDatasets(
        query,
        sender()
      )

    //case MiningQuery(variables, covariables, groups, _, AlgorithmSpec(c, p))
    //    if c == "" || c == "data" =>
    //case query: MiningQuery if query.algorithm.code == "" || query.algorithm.code == "data" =>
    //  featuresDatabase.queryData(jobsConf.featuresTable, query.dbAllVars)
    // TODO To be implemented

    case query: MiningQuery =>
      mark("MiningQueryRequestReceived")
      logger.info("Mining query received")
      dispatchActors.miningQueriesWorker forward MiningQueriesActor.Mine(query, sender())

    case query: ExperimentQuery =>
      mark("ExperimentQueryRequestReceived")
      logger.info("Experiment query received")
      dispatchActors.experimentQueriesWorker forward ExperimentQueriesActor.Experiment(
        query,
        sender()
      )

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private[akka] def mark(spanKey: String): Unit = {
    val _ = Kamon.currentSpan().mark(spanKey)
  }
}
