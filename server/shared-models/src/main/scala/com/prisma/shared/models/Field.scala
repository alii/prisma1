package com.prisma.shared.models

import com.prisma.gc_values.GCValue
import com.prisma.shared.models.FieldBehaviour.{CreatedAtBehaviour, IdBehaviour, UpdatedAtBehaviour}
import com.prisma.shared.models.Manifestations._

import scala.language.implicitConversions

object RelationSide extends Enumeration {
  type RelationSide = Value
  val A = Value("A")
  val B = Value("B")

  def opposite(side: RelationSide.Value) = if (side == A) B else A
}

object TypeIdentifier {
  sealed trait TypeIdentifier {
    def code: String
    def userFriendlyTypeName: String = code
  }

  object Relation extends TypeIdentifier { def code = "Relation" }

  sealed trait ScalarTypeIdentifier extends TypeIdentifier
  object String                     extends ScalarTypeIdentifier { def code = "String" }
  object Float                      extends ScalarTypeIdentifier { def code = "Float" }
  object Boolean                    extends ScalarTypeIdentifier { def code = "Boolean" }
  object Enum                       extends ScalarTypeIdentifier { def code = "Enum" }
  object Json                       extends ScalarTypeIdentifier { def code = "Json" }
  object DateTime                   extends ScalarTypeIdentifier { def code = "DateTime" }

  sealed trait IdTypeIdentifier extends ScalarTypeIdentifier
  object Cuid                   extends IdTypeIdentifier { def code = "GraphQLID"; override def userFriendlyTypeName = "ID" }
  object UUID                   extends IdTypeIdentifier { def code = "UUID" }
  object Int                    extends IdTypeIdentifier { def code = "Int" }

  // compatibility with Enumeration interface
  type Value = TypeIdentifier

  private val instances = Vector(Relation, String, Int, Float, Boolean, Enum, Json, DateTime, Cuid, UUID)

  def withName(name: String): TypeIdentifier = withNameOpt(name).getOrElse(throw new NoSuchElementException(s"No value found for '$name'"))

  def withNameOpt(name: String): Option[TypeIdentifier] = name match {
    case "ID" => Some(Cuid)
    case _    => instances.find(_.code == name)
  }

}

case class Enum(
    name: String,
    values: Vector[String] = Vector.empty
)

case class FieldTemplate(
    name: String,
    typeIdentifier: TypeIdentifier.Value,
    isRequired: Boolean,
    isList: Boolean,
    isUnique: Boolean,
    isHidden: Boolean = false,
    isAutoGeneratedByDb: Boolean = false,
    enum: Option[Enum],
    defaultValue: Option[GCValue],
    relationName: Option[String],
    relationSide: Option[RelationSide.Value],
    manifestation: Option[FieldManifestation],
    behaviour: Option[FieldBehaviour]
) {
  def build(model: Model): Field = {
    typeIdentifier match {
      case TypeIdentifier.Relation =>
        RelationField(
          name = name,
          isRequired = isRequired,
          isList = isList,
          isHidden = isHidden,
          relationName = relationName.get,
          relationSide = relationSide.get,
          template = this,
          model = model
        )
      case ti: TypeIdentifier.ScalarTypeIdentifier =>
        ScalarField(
          name = name,
          typeIdentifier = ti,
          isRequired = isRequired,
          isList = isList,
          isHidden = isHidden,
          isAutoGeneratedByDb = isAutoGeneratedByDb,
          enum = enum,
          defaultValue = defaultValue,
          manifestation = manifestation,
          template = this,
          model = model
        )

    }
  }
}

object Field {
  val magicalBackRelationPrefix = "_MagicalBackRelation_"
}

sealed trait Field {
  def name: String
  def typeIdentifier: TypeIdentifier.Value
  def isRequired: Boolean
  def isList: Boolean
  def isUnique: Boolean
  def isHidden: Boolean
  def enum: Option[Enum]
  def defaultValue: Option[GCValue]
  def relationOpt: Option[Relation]
  def model: Model
  def schema: Schema
  def template: FieldTemplate
  def isRelation: Boolean
  def isScalar: Boolean
  def dbName: String
  def behaviour: Option[FieldBehaviour] = template.behaviour

  lazy val isScalarList: Boolean      = isScalar && isList
  lazy val isScalarNonList: Boolean   = isScalar && !isList
  lazy val isRelationList: Boolean    = isRelation && isList
  lazy val isRelationNonList: Boolean = isRelation && !isList
  lazy val isVisible: Boolean         = !isHidden

  val isMagicalBackRelation = name.startsWith(Field.magicalBackRelationPrefix)

  def asScalarField_! : ScalarField     = this.asInstanceOf[ScalarField]
  def asRelationField_! : RelationField = this.asInstanceOf[RelationField]

  def userFriendlyTypeName: String
}

case class RelationField(
    name: String,
    isRequired: Boolean,
    isList: Boolean,
    isHidden: Boolean,
    relationName: String,
    relationSide: RelationSide.Value,
    template: FieldTemplate,
    model: Model
) extends Field {
  override def typeIdentifier       = TypeIdentifier.Relation
  override def isRelation           = true
  override def isScalar             = false
  override def isUnique             = false
  override def enum                 = None
  override def defaultValue         = None
  override def schema               = model.schema
  override def userFriendlyTypeName = relatedModel_!.name

  lazy val dbName: String = relation.manifestation match {
    case m: EmbeddedRelationLink if relation.isSelfRelation && isHidden                                                  => this.name
    case m: EmbeddedRelationLink if relation.isSelfRelation && (relationSide == RelationSide.B || relatedField.isHidden) => m.referencingColumn
    case m: EmbeddedRelationLink if relation.isSelfRelation && relationSide == RelationSide.A                            => this.name
    case m: EmbeddedRelationLink if m.inTableOfModelName == model.name                                                   => m.referencingColumn
    case m: EmbeddedRelationLink if m.inTableOfModelName == relatedModel_!.name                                          => this.name
    case _                                                                                                               => this.name
  }

  lazy val relationIsInlinedInParent = relation.manifestation match {
    case m: EmbeddedRelationLink if relation.isSelfRelation && isHidden                                                  => false
    case m: EmbeddedRelationLink if relation.isSelfRelation && (relationSide == RelationSide.B || relatedField.isHidden) => true
    case m: EmbeddedRelationLink if relation.isSelfRelation && relationSide == RelationSide.A                            => false
    case m: EmbeddedRelationLink if m.inTableOfModelName == model.name                                                   => true
    case m: EmbeddedRelationLink if m.inTableOfModelName == relatedModel_!.name                                          => false
    case _                                                                                                               => false
  }

  lazy val relation: Relation            = schema.getRelationByName_!(relationName)
  lazy val relationOpt: Option[Relation] = Some(relation)

  lazy val relatedModel_! : Model = {
    relationSide match {
      case RelationSide.A => relation.modelB
      case RelationSide.B => relation.modelA
      case x              => sys.error(s"received invalid relation side $x")
    }
  }

  lazy val relatedField: RelationField = {
    relatedModel_!.relationFields
      .find { field =>
        val relation          = field.relation
        val isTheSameField    = field == this
        val isTheSameRelation = relation == this.relation
        isTheSameRelation && !isTheSameField
      }
      .getOrElse {
        sys.error(s"Could not find related field of $name on model ${model.name}. Relation is ${relation.name}")
      }
  }

  lazy val oppositeRelationSide: RelationSide.Value = {
    relationSide match {
      case RelationSide.A => RelationSide.B
      case RelationSide.B => RelationSide.A
      case x              => sys.error(s"received invalid relation side $x")
    }
  }

  def isRelationWithNameAndSide(relationName: String, side: RelationSide.Value): Boolean = relation.name == relationName && this.relationSide == side

  def scalarCopy: ScalarField = {
    model.idField_!.copy(
      name = this.name,
      typeIdentifier = this.relatedModel_!.idField_!.typeIdentifier,
      isList = this.isList,
      manifestation = this.relation.inlineManifestation.map(x => FieldManifestation(x.referencingColumn))
    )
  }
}

case class ScalarField(
    name: String,
    typeIdentifier: TypeIdentifier.ScalarTypeIdentifier,
    isRequired: Boolean,
    isList: Boolean,
    isHidden: Boolean,
    isAutoGeneratedByDb: Boolean,
    enum: Option[Enum],
    defaultValue: Option[GCValue],
    manifestation: Option[FieldManifestation],
    template: FieldTemplate,
    model: Model
) extends Field {
  import ReservedFields._

  override def isRelation                   = false
  override def isScalar                     = true
  override def relationOpt: None.type       = None
  override val dbName: String               = manifestation.map(_.dbName).getOrElse(name)
  override def isUnique: Boolean            = template.isUnique || behaviour.exists(_.isInstanceOf[IdBehaviour])
  lazy val isWritableDuringUpdate: Boolean  = !isId && !isCreatedAt && !isUpdatedAt
  override def schema: Schema               = model.schema
  override def userFriendlyTypeName: String = typeIdentifier.userFriendlyTypeName

  val isId: Boolean        = if (model.isLegacy) name == idFieldName || name == embeddedIdFieldName else behaviour.exists(_.isInstanceOf[IdBehaviour])
  val isCreatedAt: Boolean = if (model.isLegacy) name == ReservedFields.createdAtFieldName else behaviour.contains(CreatedAtBehaviour)
  val isUpdatedAt: Boolean = if (model.isLegacy) name == ReservedFields.updatedAtFieldName else behaviour.contains(UpdatedAtBehaviour)
}
