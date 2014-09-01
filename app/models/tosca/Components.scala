/* 
** Copyright [2013-2014] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.tosca

import scalaz._
import scalaz.syntax.SemigroupOps
import scalaz.NonEmptyList._
import scalaz.effect.IO
import scalaz.Validation._
import scalaz.EitherT._
import Scalaz._
import org.megam.util.Time
import controllers.stack._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import models.tosca._
import models.Accounts
import models.cache._
import models.riak._
import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import org.megam.common.riak.{ GSRiak, GunnySack }
import org.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

/**
 * @author rajthilak
 *
 */

case class ComponentRequirements(host: String) {
  val json = "{\"host\":\"" + host + "\"}"
}

object ComponentRequirements {
  def empty: ComponentRequirements = new ComponentRequirements(new String())
}

case class ComponentInputs(id: String, x: String, y: String, z: String) {
  val json = "{\"id\":\"" + id + "\",\"x\":\"" + x + "\",\"y\":\"" + y + "\",\"z\":\"" + z + "\"}"
}

object ComponentInputs {
  def empty: ComponentInputs = new ComponentInputs(new String(), new String(), new String, new String())
}

case class ExResource(url: String) {
  val json = "{\"url\":\"" + url + "\"}"
}

object ExResource {
  def empty: ExResource = new ExResource(new String())
}

//case class Artifacts(artifact_type: String, content: String, requirements: ArtifactRequirements) {
case class Artifacts(artifact_type: String, content: String, requirements: String) {
  val json = "{\"artifact_type\":\"" + artifact_type + "\",\"content\":\"" + content + "\",\"requirements\":\"" + requirements + "\"}"
}

object Artifacts {
  //  def empty: Artifacts = new Artifacts(new String(), new String(), ArtifactRequirements.empty)  
  def empty: Artifacts = new Artifacts(new String(), new String(), new String())
}

case class ArtifactRequirements(requirement_type: String) {
  val json = "{\"requirement_type\":\"" + requirement_type + "\"}"
}

object ArtifactRequirements {
  def empty: ArtifactRequirements = new ArtifactRequirements(new String())
}

case class ComponentOperations(operation_type: String, target_resource: String) {
  val json = "{\"operation_type\":\"" + operation_type + "\",\"target_resource\":\"" + target_resource + "\"}"
}

object ComponentOperations {
  def empty: ComponentOperations = new ComponentOperations(new String(), new String())
}

case class ComponentResult(id: String, name: String, tosca_type: String, requirements: String, inputs: ComponentInputs, external_management_resource: String, artifacts: Artifacts, related_components: String, operations: ComponentOperations, created_at: String) {
  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.ComponentResultSerialization
    val preser = new ComponentResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object ComponentResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[ComponentResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.ComponentResultSerialization
    val preser = new ComponentResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[ComponentResult] = (Validation.fromTryCatch {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

//case class Component(name: String, tosca_type: String, requirements: ComponentRequirements, inputs: ComponentInputs, external_management_resource: ExResource, artifacts: Artifacts, related_components: String, operations: ComponentOperations) {
case class Component(name: String, tosca_type: String, requirements: String, inputs: ComponentInputs, external_management_resource: String, artifacts: Artifacts, related_components: String, operations: ComponentOperations) {
  val json = "{\"name\":\"" + name + "\",\"tosca_type\":\"" + tosca_type + "\",\"requirements\":\"" + requirements +
    "\",\"inputs\":" + inputs.json + ",\"external_management_resource\":\"" + external_management_resource + "\",\"artifacts\":" + artifacts.json +
    ",\"related_components\":\"" + related_components + "\",\"operations\":" + operations.json + "}"

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new models.json.tosca.ComponentSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object Component {
  // def empty: Component = new Component(new String(), new String(), ComponentRequirements.empty, ComponentInputs.empty, ExResource.empty, Artifacts.empty, new String(), ComponentOperations.empty)
  def empty: Component = new Component(new String(), new String(), new String(), ComponentInputs.empty, new String(), Artifacts.empty, new String(), ComponentOperations.empty)

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[Component] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new models.json.tosca.ComponentSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[Component] = (Validation.fromTryCatch {
    play.api.Logger.debug(("%-20s -->[%s]").format("---json------------------->", json))
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

object ComponentsList {

  implicit val formats = DefaultFormats

  implicit def ComponentsResultsSemigroup: Semigroup[ComponentsResults] = Semigroup.instance((f1, f2) => f1.append(f2))

  val emptyRR = List(Component.empty)
  def toJValue(nres: ComponentsList): JValue = {

    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.ComponentsListSerialization.{ writer => ComponentsListWriter }
    toJSON(nres)(ComponentsListWriter)
  }

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[ComponentsList] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.ComponentsListSerialization.{ reader => ComponentsListReader }
    fromJSON(jValue)(ComponentsListReader)
  }

  def toJson(nres: ComponentsList, prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue(nres)))
  } else {
    compactRender(toJValue(nres))
  }

  def apply(componentList: List[Component]): ComponentsList = { println(componentList); componentList }

  def empty: List[Component] = emptyRR

  private val riak = GWRiak("components")

  val metadataKey = "COMPONENT"
  val metadataVal = "Component Creation"
  val bindex = "component"

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to nodeinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkGunnySack(email: String, input: Component): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.Component", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    for {
      aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "com").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      val bvalue = Set(aor.get.id)
      val json = "{\"id\": \"" + (uir.get._1 + uir.get._2) + "\",\"name\":\"" + input.name + "\",\"tosca_type\":\"" + input.tosca_type + "\",\"requirements\":\"" + input.requirements + "\",\"inputs\":" + input.inputs.json + ",\"external_management_resource\":\"" + input.external_management_resource + "\",\"artifacts\":" + input.artifacts.json + ",\"related_components\":\"" + input.related_components + "\",\"operations\":" + input.operations.json + ",\"created_at\":\"" + Time.now.toString + "\"}"
      new GunnySack((uir.get._1 + uir.get._2), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  def createLinks(email: String, input: ComponentsList): ValidationNel[Throwable, ComponentsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.ComponentsList", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("yaml", input))

    val res = (input map {
      asminp =>
        play.api.Logger.debug(("%-20s -->[%s]").format("component", asminp))
        (create(email, asminp))
    }).foldRight((ComponentsResults.empty).successNel[Throwable])(_ +++ _)

    play.api.Logger.debug(("%-20s -->[%s]").format("models.tosca.Assembly", res))
    res.getOrElse(new ResourceItemNotFound(email, "nodes = ah. ouh. ror some reason.").failureNel[ComponentsResults])
    res
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name assemblies name will point to the "csars" bucket
   */
  def create(email: String, input: Component): ValidationNel[Throwable, ComponentsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.ComponentsList", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("yaml", input))

    for {
      ogsi <- mkGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] => err }
      nrip <- ComponentResult.fromJson(ogsi.get.value) leftMap { t: NonEmptyList[net.liftweb.json.scalaz.JsonScalaz.Error] => println("osgi\n" + ogsi.get.value); play.api.Logger.debug(JSONParsingError(t).toString); nels(JSONParsingError(t)) }
      ogsr <- riak.store(ogsi.get) leftMap { t: NonEmptyList[Throwable] => play.api.Logger.debug("--------> ooo"); t }
    } yield {
      play.api.Logger.debug(("%-20s -->[%s],riak returned: %s").format("Component.created successfully", email, ogsr))
      ogsr match {
        case Some(thatGS) => {
          nels(ComponentResult(thatGS.key, nrip.name, nrip.tosca_type, nrip.requirements, nrip.inputs, nrip.external_management_resource, nrip.artifacts, nrip.related_components, nrip.operations, Time.now.toString()).some)
        }
        case None => {
          play.api.Logger.warn(("%-20s -->[%s]").format("Node.created successfully", "Scaliak returned => None. Thats OK."))
          nels(ComponentResult(ogsi.get.key, nrip.name, nrip.tosca_type, nrip.requirements, nrip.inputs, nrip.external_management_resource, nrip.artifacts, nrip.related_components, nrip.operations, Time.now.toString()).some)
        }
      }
    }
  }

}
 