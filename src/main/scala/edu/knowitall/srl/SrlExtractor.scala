package edu.knowitall.srl

import scala.io.Source
import edu.knowitall.tool.parse.ClearParser
import edu.knowitall.tool.srl.ClearSrl
import edu.knowitall.tool.parse.graph.DependencyGraph
import scala.util.control.Exception
import java.io.File
import edu.knowitall.common.Resource
import edu.knowitall.tool.srl.FrameHierarchy
import edu.knowitall.tool.srl.Frame

class SrlExtractor(val srl: ClearSrl = new ClearSrl()) {
  def apply(dgraph: DependencyGraph): Seq[SrlExtraction] = {
    val frames = srl.apply(dgraph)
    this.extract(dgraph)(frames)
  }

  def extract(dgraph: DependencyGraph)(frames: Seq[Frame]) = {
    val hierarchy = FrameHierarchy.fromFrames(dgraph, frames).toSeq
    hierarchy flatMap SrlExtraction.fromFrameHierarchy(dgraph)
  }
}

object SrlExtractor extends App {
  case class Config(inputFile: Option[File] = None) {
    def source() = {
      inputFile match {
        case Some(file) => Source.fromFile(file)
        case None => Source.stdin
      }
    }
  }

  val argumentParser = new scopt.immutable.OptionParser[Config]("srl-ie") {
    def options = Seq(
      argOpt("input file", "input file") { (string, config) =>
        val file = new File(string)
        require(file.exists, "file does not exist: " + file)
        config.copy(inputFile = Some(file))
      })
  }

  argumentParser.parse(args, Config()) match {
    case Some(config) => run(config)
    case None =>
  }

  def run(config: Config) {
    lazy val parser = new ClearParser()
    val srl = new ClearSrl()

    def graphify(line: String) = {
      (Exception.catching(classOf[DependencyGraph.SerializationException]) opt DependencyGraph.deserialize(line)) match {
        case Some(graph) => graph
        case None => parser.dependencyGraph(line)
      }
    }

    Resource.using(config.source()) { source =>
      for (line <- source.getLines) {
        val graph = graphify(line)
        println(graph.serialize)

        println("frames:")
        val frames = srl.apply(graph)
        frames.map(_.serialize) foreach println

        println("hierarchy:")
        val hierarchy = FrameHierarchy.fromFrames(graph, frames.toIndexedSeq)
        hierarchy foreach println

        println("extractions:")
        val extrs = frames flatMap SrlExtraction.fromFrame(graph)
        extrs foreach println

        println("triples:")
        val triples = extrs flatMap (_.triplize(true))
        triples foreach println
      }
    }
  }
}