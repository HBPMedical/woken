/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core.validation

import eu.hbp.mip.woken.messages.external.{ ExperimentQuery, Validation }
import org.scalatest._

class KFoldCrossValidationTest extends FlatSpec with Matchers {

  "An experiment JSON object " should "be readable" in {

    import eu.hbp.mip.woken.api.ApiJsonSupport._
    import spray.json._

    val source =
      """
        |{
        |  "variables":[{"code":"LeftAmygdala"}],
        |  "grouping":[{"code":"COLPROT"}],
        |  "covariables":[{"code":"AGE"}],
        |  "filters":"",
        |  "algorithms":[
        |    {"code":"linearRegression", "name": "linearRegression", "parameters": []}
        |  ],
        |  "validations":[
        |    {"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}
        |  ]
        |}
        |
        |""".stripMargin
    val jsonAst    = source.parseJson
    val validation = jsonAst.convertTo[ExperimentQuery]

    println(validation)
  }

  "A validation JSON object " should "be readable" in {

    import eu.hbp.mip.woken.api.ApiJsonSupport._
    import spray.json._

    val source =
      """
        |{
        |  "code":"kfold",
        |  "name": "kfold",
        |  "parameters": [{"code": "k", "value": "2"}]
        |}
        |
        |""".stripMargin
    val jsonAst    = source.parseJson
    val validation = jsonAst.convertTo[Validation]

    println(validation)
  }
}
