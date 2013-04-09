package edu.knowitall.srl

import scala.io.Source
import edu.knowitall.tool.parse.ClearParser
import edu.knowitall.tool.srl.ClearSrl
import edu.knowitall.tool.srl.Srl
import edu.knowitall.tool.parse.graph.DependencyGraph
import scala.util.control.Exception
import java.io.File
import edu.knowitall.common.Resource
import edu.knowitall.tool.srl.FrameHierarchy
import edu.knowitall.tool.srl.Frame
import edu.knowitall.tool.srl.Roles
import edu.knowitall.srl.confidence.SrlConfidenceFunction
import java.io.PrintWriter

class SrlExtractor(val srl: Srl = new ClearSrl()) {
  def apply(dgraph: DependencyGraph): Seq[SrlExtractionInstance] = {
    val frames = srl.apply(dgraph)
    this.extract(dgraph)(frames)
  }

  def extract(dgraph: DependencyGraph)(frames: Seq[Frame]) = {
    val hierarchy = FrameHierarchy.fromFrames(dgraph, frames).toSeq
    hierarchy.flatMap { hierarchy =>
      val extrs = SrlExtraction.fromFrameHierarchy(dgraph)(hierarchy)
      extrs.map { extr => SrlExtractionInstance(extr, hierarchy, dgraph) }
    }
  }
}

object SrlExtractor extends App {
  sealed abstract class OutputFormat
  object OutputFormat {
    def apply(format: String): OutputFormat = {
      format.toLowerCase match {
        case "standard" => Standard
        case "annotation" => Annotation
        case "evaluation" => Evaluation
        case _ => throw new IllegalArgumentException("Unknown output format: " + format)
      }
    }

    case object Standard extends OutputFormat
    case object Annotation extends OutputFormat
    case object Evaluation extends OutputFormat
  }

  case class Config(inputFile: Option[File] = None, outputFile: Option[File] = None, outputFormat: OutputFormat = OutputFormat.Standard, gold: Map[String, Boolean] = Map.empty) {
    def source() = {
      inputFile match {
        case Some(file) => Source.fromFile(file)
        case None => Source.stdin
      }
    }

    def writer() = {
      outputFile match {
        case Some(file) => new PrintWriter(file, "UTF8")
        case None => new PrintWriter(System.out)
      }
    }
  }

  val argumentParser = new scopt.immutable.OptionParser[Config]("srl-ie") {
    def options = Seq(
      argOpt("input file", "input file") { (string, config) =>
        val file = new File(string)
        require(file.exists, "input file does not exist: " + file)
        config.copy(inputFile = Some(file))
      },
      argOpt("ouput file", "output file") { (string, config) =>
        val file = new File(string)
        config.copy(outputFile = Some(file))
      },
      opt("gold", "gold file") { (string, config) =>
        val file = new File(string)
        require(file.exists, "gold file does not exist: " + file)
        val gold = Resource.using (Source.fromFile(file)) { source =>
          (for {
            line <- source.getLines
            Array(annotation, string, _ @ _*) = line.split("\t")
            boolean = if (annotation == "1") true else false
          } yield {
            string -> boolean
          }).toMap
        }
        config.copy(gold = gold)
      },
      opt("format", "output format: {standard, annotation}") { (string, config) =>
        config.copy(outputFormat = OutputFormat(string))
      })
  }

  argumentParser.parse(args, Config()) match {
    case Some(config) => run(config)
    case None =>
  }

  def run(config: Config) {
    lazy val parser = new ClearParser()
    val srl = new SrlExtractor()
    val conf = SrlConfidenceFunction.loadDefaultClassifier()

    def graphify(line: String) = {
      (Exception.catching(classOf[DependencyGraph.SerializationException]) opt DependencyGraph.deserialize(line)) match {
        case Some(graph) => graph
        case None => parser.dependencyGraph(line)
      }
    }

    Resource.using(config.source()) { source =>
      Resource.using(config.writer()) { writer =>
        for (line <- source.getLines) {
          val graph = graphify(line)
          val insts = srl.apply(graph)

          if (config.outputFormat == OutputFormat.Standard) {
            writer.println(graph.serialize)
            writer.println()

            writer.println("extractions:")
            insts.map(_.extr) foreach writer.println
            writer.println()

            writer.println("triples:")
            insts.flatMap(_.triplize(true)).map(_.extr) foreach writer.println
          } else if (config.outputFormat == OutputFormat.Annotation) {
            for (inst <- insts) {
              val extr = inst.extr
              writer.println(Iterable(config.gold.get(extr.toString).map(if (_) 1 else 0).getOrElse(""), extr.toString, extr.arg1, extr.relation, extr.arg2s.mkString("; "), line).mkString("\t"))
            }
          } else if (config.outputFormat == OutputFormat.Evaluation) {
            for (inst <- insts) {
              val extr = inst.extr
              writer.println(Iterable(config.gold.get(extr.toString).map(if (_) 1 else 0).getOrElse(""), conf(inst), extr.toString, extr.arg1, extr.relation, extr.arg2s.mkString("; "), line).mkString("\t"))
            }
          }
        }
      }
    }
  }
}
