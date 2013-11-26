package scala.slick.driver

import scala.language.implicitConversions
import scala.slick.ast.{Node, TypedType, BaseTypedType}
import scala.slick.compiler.{Phase, QueryCompiler}
import scala.slick.lifted._
import scala.slick.jdbc.{MappedJdbcType, JdbcMappingCompilerComponent, JdbcType, MutatingUnitInvoker, JdbcBackend}
import scala.slick.profile.{SqlDriver, SqlProfile, Capability}
import scala.slick.SlickException
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.meta.MColumn
import scala.slick.ast.ColumnOption
import scala.slick.meta.Model
import scala.slick.jdbc.UnitInvoker
import scala.slick.jdbc.meta.createMetaModel

/**
 * A profile for accessing SQL databases via JDBC.
 */
trait JdbcProfile extends SqlProfile with JdbcTableComponent
  with JdbcInvokerComponent with JdbcExecutorComponent { driver: JdbcDriver =>

  type Backend = JdbcBackend
  val backend: Backend = JdbcBackend
  val compiler = QueryCompiler.relational
  val Implicit: Implicits = new Implicits {}
  val simple: SimpleQL = new SimpleQL {}
  type ColumnType[T] = JdbcType[T]
  type BaseColumnType[T] = JdbcType[T] with BaseTypedType[T]
  val columnTypes = new JdbcTypes

  override protected def computeCapabilities = super.computeCapabilities ++ JdbcProfile.capabilities.all

  lazy val queryCompiler = compiler + new JdbcCodeGen(_.buildSelect)
  lazy val updateCompiler = compiler + new JdbcCodeGen(_.buildUpdate)
  lazy val deleteCompiler = compiler + new JdbcCodeGen(_.buildDelete)
  lazy val insertCompiler = QueryCompiler(Phase.inline, Phase.assignUniqueSymbols, new JdbcInsertCompiler)

  final def buildTableSchemaDescription(table: Table[_]): DDL = createTableDDLBuilder(table).buildDDL
  final def buildSequenceSchemaDescription(seq: Sequence[_]): DDL = createSequenceDDLBuilder(seq).buildDDL

  trait LowPriorityImplicits {
    implicit def queryToAppliedQueryInvoker[T, U](q: Query[T, _ <: U]): UnitQueryInvoker[U] = createUnitQueryInvoker[U](queryCompiler.run(q.toNode).tree)
    implicit def queryToUpdateInvoker[E, U](q: Query[E, U]): UpdateInvoker[U] = createUpdateInvoker(updateCompiler.run(q.toNode).tree, ())
  }

  trait Implicits extends LowPriorityImplicits with super.Implicits with ImplicitColumnTypes {
    implicit def ddlToDDLInvoker(d: DDL): DDLInvoker = createDDLInvoker(d)
    implicit def queryToDeleteInvoker(q: Query[_ <: Table[_], _]): DeleteInvoker = createDeleteInvoker(deleteCompiler.run(q.toNode).tree, ())
    implicit def runnableCompiledToAppliedQueryInvoker[RU](c: RunnableCompiled[_ <: Query[_, _], Seq[RU]]): MutatingUnitInvoker[RU] = createQueryInvoker[Any, RU](c.compiledQuery)(c.param)
    implicit def runnableCompiledToUpdateInvoker[RU](c: RunnableCompiled[_ <: Query[_, _], Seq[RU]]): UpdateInvoker[RU] =
      createUpdateInvoker(c.compiledUpdate, c.param)
    implicit def runnableCompiledToDeleteInvoker[RU](c: RunnableCompiled[_ <: Query[_, _], Seq[RU]]): DeleteInvoker =
      createDeleteInvoker(c.compiledDelete, c.param)

    // This conversion only works for fully packed types
    implicit def productQueryToUpdateInvoker[T](q: Query[_ <: ColumnBase[T], T]): UpdateInvoker[T] =
      createUpdateInvoker(updateCompiler.run(q.toNode).tree, ())
  }

  trait SimpleQL extends super.SimpleQL with Implicits {
    type MappedColumnType[T, U] = MappedJdbcType[T, U]
    val MappedColumnType = MappedJdbcType
  }

  /**
   * Jdbc meta data for all tables
   */
  def getTables: UnitInvoker[MTable] = MTable.getTables()

  /** Gets the Slick meta model describing this data source */
  def metaModel(implicit session: Backend#Session): Model = createMetaModel(getTables.list,this)

  /** Generates the ColumnOptions for the given MColumn */
  def optionsFromColumn(column: MColumn): Set[ColumnOption[_]] = {
    val IntValue = "^([0-9]*)$".r
    val DoubleValue = "^([0-9*]\\.[0-9]*)$".r
    val StringValue = """^'(.+)'$""".r
    import ColumnOption._
    Set(DBType(column.typeName + column.size.map("("+_+")").getOrElse(""))) ++
      (if(column.isAutoInc.getOrElse(false)) Some(AutoInc) else None) ++
      (column.columnDef.collect{
         case IntValue(value) => value.toInt
         case DoubleValue(value) => value.toDouble
         case StringValue(value) => value
         case "NULL" => None
       }.map(Default.apply))
  }
}

object JdbcProfile {
  object capabilities {
    /** Can insert into AutoInc columns. */
    val forceInsert = Capability("jdbc.forceInsert")
    /** Supports mutable result sets */
    val mutable = Capability("jdbc.mutable")
    /** Can return primary key of inserted row */
    val returnInsertKey = Capability("jdbc.returnInsertKey")
    /** Can also return non-primary-key columns of inserted row */
    val returnInsertOther = Capability("jdbc.returnInsertOther")

    /** Supports all JdbcProfile features which do not have separate capability values */
    val other = Capability("jdbc.other")

    /** All JDBC capabilities */
    val all = Set(other, forceInsert, mutable, returnInsertKey, returnInsertOther)
  }
}

trait JdbcDriver extends SqlDriver
  with JdbcProfile
  with JdbcStatementBuilderComponent
  with JdbcMappingCompilerComponent
  with JdbcTypesComponent { driver =>

  override val profile: JdbcProfile = this

  def quote[T](v: T)(implicit tm: TypedType[T]): String = typeInfoFor(tm).valueToSQLLiteral(v)
}

object JdbcDriver extends JdbcDriver
