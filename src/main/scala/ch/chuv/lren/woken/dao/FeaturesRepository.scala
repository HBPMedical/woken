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

package ch.chuv.lren.woken.dao

import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.TableDescription
import ch.chuv.lren.woken.messages.datasets.DatasetId
import spray.json.JsObject

import scala.language.higherKinds

/**
  * The interface to Features database
  */
trait FeaturesRepository[F[_]] extends Repository {

  def tables: Set[TableDescription]

  def featuresTable(table: String): Option[FeaturesTableRepository[F]]

}

case class ColumnMeta(index: Int, label: String, dataType: String)

trait FeaturesTableRepository[F[_]] extends Repository {

  def count: F[Int]

  def count(dataset: DatasetId): F[Int]

  type Headers = List[ColumnMeta]

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])]

}
