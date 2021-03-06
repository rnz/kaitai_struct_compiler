package io.kaitai.struct

import io.kaitai.struct.exprlang.DataType._
import io.kaitai.struct.format._
import io.kaitai.struct.languages._
import io.kaitai.struct.languages.components.{LanguageCompiler, LanguageCompilerStatic}

import scala.collection.mutable.ListBuffer

class ClassCompiler(val topClass: ClassSpec, val lang: LanguageCompiler) extends AbstractCompiler {
  val provider = new ClassTypeProvider(topClass)

  val topClassName = List(topClass.meta.get.id)

  override def compile {
    lang.open(topClassName.head, provider)

    lang.fileHeader(topClassName.head)
    compileClass(topClass)
    lang.fileFooter(topClassName.head)
    lang.close
  }

  def compileClass(curClass: ClassSpec): Unit = {
    provider.nowClass = curClass

    lang.classHeader(curClass.name)

    val extraAttrs = ListBuffer[AttrSpec]()
    extraAttrs += AttrSpec(RootIdentifier, UserTypeInstream(topClassName))
    extraAttrs += AttrSpec(ParentIdentifier, UserTypeInstream(curClass.parentTypeName))

    // Forward declarations for recursive types
    curClass.types.foreach { case (typeName, intClass) => lang.classForwardDeclaration(List(typeName)) }

    if (lang.innerEnums)
      compileEnums(curClass)

    if (lang.debug)
      lang.debugClassSequence(curClass.seq)

    lang.classConstructorHeader(curClass.name, curClass.parentTypeName, topClassName)
    curClass.instances.foreach { case (instName, instSpec) => lang.instanceClear(instName) }
    curClass.seq.foreach((attr) => lang.attrParse(attr, attr.id, extraAttrs, lang.normalIO))
    lang.classConstructorFooter

    lang.classDestructorHeader(curClass.name, curClass.parentTypeName, topClassName)
    curClass.seq.foreach((attr) => lang.attrDestructor(attr, attr.id))
    curClass.instances.foreach { case (id, instSpec) =>
      instSpec match {
        case pis: ParseInstanceSpec => lang.attrDestructor(pis, id)
        case _: ValueInstanceSpec => // ignore for now
      }
    }
    lang.classDestructorFooter

    // Recursive types
    if (lang.innerClasses) {
      compileSubclasses(curClass)

      provider.nowClass = curClass
    }

    curClass.instances.foreach { case (instName, instSpec) => compileInstance(curClass.name, instName, instSpec, extraAttrs) }

    // Attributes declarations and readers
    (curClass.seq ++ extraAttrs).foreach((attr) => lang.attributeDeclaration(attr.id, attr.dataTypeComposite, attr.cond))
    (curClass.seq ++ extraAttrs).foreach { (attr) =>
      attr.doc.foreach((doc) => lang.attributeDoc(attr.id, doc))
      lang.attributeReader(attr.id, attr.dataTypeComposite, attr.cond)
    }

    lang.classFooter(curClass.name)

    if (!lang.innerClasses)
      compileSubclasses(curClass)

    if (!lang.innerEnums)
      compileEnums(curClass)
  }

  def compileEnums(curClass: ClassSpec): Unit =
    curClass.enums.foreach { case(enumName, enumColl) => compileEnum(curClass, enumName, enumColl) }

  def compileSubclasses(curClass: ClassSpec): Unit =
    curClass.types.foreach { case (typeName, intClass) => compileClass(intClass) }

  def compileInstance(className: List[String], instName: InstanceIdentifier, instSpec: InstanceSpec, extraAttrs: ListBuffer[AttrSpec]): Unit = {
    // Determine datatype
    val dataType = TypeProcessor.getInstanceDataType(instSpec)

    // Declare caching variable
    lang.instanceDeclaration(instName, dataType, ConditionalSpec(None, NoRepeat))

    instSpec.doc.foreach((doc) => lang.attributeDoc(instName, doc))
    lang.instanceHeader(className, instName, dataType)
    lang.instanceCheckCacheAndReturn(instName)

    instSpec match {
      case vi: ValueInstanceSpec =>
        lang.instanceCalculate(instName, dataType, vi.value)
      case i: ParseInstanceSpec =>
        val io = i.io match {
          case None => lang.normalIO
          case Some(ex) => lang.useIO(ex)
        }
        i.pos.foreach { pos =>
          lang.pushPos(io)
          lang.seek(io, pos)
        }
        lang.attrParse(i, instName, extraAttrs, io)
        i.pos.foreach((pos) => lang.popPos(io))
    }

    lang.instanceSetCalculated(instName)
    lang.instanceReturn(instName)
    lang.instanceFooter
  }

  def compileEnum(curClass: ClassSpec, enumName: String, enumColl: Map[Long, String]): Unit = {
    // Stabilize order of generated enums by sorting it by integer ID - it
    // both looks nicer and doesn't screw diffs in generated code
    val enumSorted = enumColl.toSeq.sortBy(_._1)
    lang.enumDeclaration(curClass.name, enumName, enumSorted)
  }
}

object ClassCompiler {
  def fromClassSpecToFile(topClass: ClassSpec, lang: LanguageCompilerStatic, outDir: String, config: RuntimeConfig): AbstractCompiler = {
    val outPath = lang.outFilePath(config, outDir, topClass.meta.get.id)
    if (config.verbose)
      Console.println(s"... => ${outPath}")
    lang match {
      case GraphvizClassCompiler =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new GraphvizClassCompiler(topClass, out)
      case CppCompiler =>
        val outSrc = new FileLanguageOutputWriter(s"$outPath.cpp", lang.indent)
        val outHdr = new FileLanguageOutputWriter(s"$outPath.h", lang.indent)
        new ClassCompiler(topClass, new CppCompiler(config, outSrc, outHdr))
      case _ =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new ClassCompiler(topClass, getCompiler(lang, config, out))
    }
  }

  def fromClassSpecToString(topClass: ClassSpec, lang: LanguageCompilerStatic, config: RuntimeConfig):
    (StringLanguageOutputWriter, Option[StringLanguageOutputWriter], AbstractCompiler) = {
    lang match {
      case GraphvizClassCompiler =>
        val out = new StringLanguageOutputWriter(lang.indent)
        (out, None, new GraphvizClassCompiler(topClass, out))
      case CppCompiler =>
        val outSrc = new StringLanguageOutputWriter(lang.indent)
        val outHdr = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, new CppCompiler(config, outSrc, outHdr))
        (outSrc, Some(outHdr), cc)
      case _ =>
        val out = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, getCompiler(lang, config, out))
        (out, None, cc)
    }
  }

  private def getCompiler(lang: LanguageCompilerStatic, config: RuntimeConfig, out: LanguageOutputWriter) = lang match {
    case CSharpCompiler => new CSharpCompiler(config, out)
    case JavaCompiler => new JavaCompiler(config, out)
    case JavaScriptCompiler => new JavaScriptCompiler(config, out)
    case PerlCompiler => new PerlCompiler(config, out)
    case PHPCompiler => new PHPCompiler(config, out)
    case PythonCompiler => new PythonCompiler(config, out)
    case RubyCompiler => new RubyCompiler(config, out)
  }
}
