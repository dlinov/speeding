package io.github.dlinov.speeding

import com.bot4s.telegram.api.declarative.{Command, CommandFilterMagnet}

trait HintedCommandFilterMagnet extends CommandFilterMagnet {
  def targets: Set[String]
}

object HintedCommandFilterMagnet {
  def apply(cmds: String*): HintedCommandFilterMagnet = new HintedCommandFilterMagnet {
    override def targets: Set[String] = cmds.toSet

    override def accept(c: Command): Boolean = targets.exists(_.equalsIgnoreCase(c.cmd))
  }

  val ANY: HintedCommandFilterMagnet = new HintedCommandFilterMagnet {
    override def targets: Set[String] = Set.empty
    override def accept(c: Command): Boolean = true
  }

  implicit class HintedCommandFilterOps(s: String) {
    def asHCFM: HintedCommandFilterMagnet = HintedCommandFilterMagnet {
      val target = s.trim().stripPrefix("/")
      require(target.matches("""\w+"""))
      target
    }
  }
}
