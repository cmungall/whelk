package org.geneontology.whelk

import scala.annotation.tailrec
import scala.collection.immutable.Queue

final case class Reasoner(
  todo:                   Queue[QueueExpression], // need to initialize from ont
  concIncs:               Set[ConceptInclusion], // based on ont
  concIncsBySubclass:     Map[Concept, List[ConceptInclusion]],
  inits:                  Set[Concept], // closure
  subs:                   Set[ConceptInclusion], // closure
  //subsBySubclassList:     Map[Concept, List[ConceptInclusion]],
  subsBySubclass:         Map[Concept, Set[ConceptInclusion]],
  links:                  Set[Link], // closure
  linksBySubject:         Map[Concept, List[Link]],
  linksByTarget:          Map[Concept, List[Link]],
  negConjs:               Set[Conjunction], // based on ont
  negConjsByOperand:      Map[Concept, Map[Concept, Conjunction]], // based on ont
  negConjsByOperandLeft:  Map[Concept, Map[Concept, Conjunction]], // based on ont
  negConjsByOperandRight: Map[Concept, Map[Concept, Conjunction]], // based on ont
  negExists:              Set[ExistentialRestriction], // based on ont
  hier:                   Map[Role, Set[Role]], // based on ont
  roleComps:              Map[(Role, Role), Set[Role]], // based on ont
  topOccursNegatively:    Boolean) // based on ont

object Reasoner {

  val empty: Reasoner = Reasoner(Queue.empty, Set.empty, Map.empty, Set.empty, Set.empty, Map.empty, Set.empty, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, false)

  def prepare(axioms: Set[Axiom]): Reasoner = {
    val concIncs = axioms.collect { case ci: ConceptInclusion => ci }
    val concIncsBySubclass = concIncs.groupBy(_.subclass).map { case (concept, cis) => concept -> cis.toList }
    val todo = Queue.empty.enqueue(concIncs)
    val negativeConcepts = concIncs.flatMap(_.subclass.conceptSignature)
    val negConjs = negativeConcepts.collect { case conj: Conjunction => conj }
    val negConjsByOperand = negConjs.groupBy(_.left).map { case (concept, m) => concept -> m.map(conj => conj.right -> conj).toMap }
    val negConjsByOperandLeft = negConjs.groupBy(_.left).map { case (concept, m) => concept -> m.map(conj => conj.right -> conj).toMap }
    val negConjsByOperandRight = negConjs.groupBy(_.right).map { case (concept, m) => concept -> m.map(conj => conj.left -> conj).toMap }
    val negExists = negativeConcepts.collect { case er: ExistentialRestriction => er }
    empty.copy(todo = todo, concIncs = concIncs, concIncsBySubclass = concIncsBySubclass, negConjs = negConjs, negConjsByOperand = negConjsByOperand, negExists = negExists, topOccursNegatively = negativeConcepts(Top))
  }

  @tailrec
  def computeClosure(reasoner: Reasoner): Reasoner = if (reasoner.todo.nonEmpty) {
    val (item, todo) = reasoner.todo.dequeue
    computeClosure(process(item, reasoner.copy(todo = todo)))
  } else reasoner

  private def process(expression: QueueExpression, reasoner: Reasoner): Reasoner = expression match {
    case concept: Concept => if (reasoner.inits(concept)) reasoner else
      `R⊤`(concept, R0(concept, reasoner.copy(inits = reasoner.inits + concept)))
    case ci @ ConceptInclusion(subclass, superclass) => if (reasoner.subs(ci)) reasoner else {
      val subs = reasoner.subs + ci
      //val subsBySubclass = reasoner.subsBySubclass + (ci.subclass -> (ci :: reasoner.subsBySubclass.getOrElse(ci.subclass, Nil)))
      val subsBySubclass = reasoner.subsBySubclass + (ci.subclass -> (reasoner.subsBySubclass.getOrElse(ci.subclass, Set.empty) + ci))
      `R⊑`(ci, `R+∃`(ci, `R-∃`(ci, `R+⨅`(ci, `R-⨅`(ci, `R⊥`(ci, reasoner.copy(subs = subs, subsBySubclass = subsBySubclass)))))))
    }
    case link @ Link(subclass, role, superclass) => if (reasoner.links(link)) reasoner else {
      val links = reasoner.links + link
      val linksBySubject = reasoner.linksBySubject + (link.subject -> (link :: reasoner.linksBySubject.getOrElse(link.subject, Nil)))
      val linksByTarget = reasoner.linksByTarget + (link.target -> (link :: reasoner.linksByTarget.getOrElse(link.target, Nil)))
      `R⤳`(link, `R∘`(link, `R+∃`(link, `R⊥`(link, reasoner.copy(links = links, linksBySubject = linksBySubject, linksByTarget = linksByTarget)))))
    }
  }

  private def R0(concept: Concept, reasoner: Reasoner): Reasoner =
    reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(concept, concept)))

  private def `R⊤`(concept: Concept, reasoner: Reasoner): Reasoner =
    if (reasoner.topOccursNegatively) reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(concept, Top)))
    else reasoner

  private def `R⊥`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner =
    if (ci.superclass == Bottom) reasoner.copy(todo = reasoner.todo
      .enqueue(reasoner.linksByTarget.getOrElse(ci.subclass, Nil).map(link => ConceptInclusion(link.subject, Bottom))))
    else reasoner

  private def `R-⨅`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = ci match {
    case ConceptInclusion(sub, Conjunction(left, right)) => reasoner.copy(todo = reasoner.todo
      .enqueue(ConceptInclusion(sub, left))
      .enqueue(ConceptInclusion(sub, right)))
    case _ => reasoner
  }

  private def `R+⨅`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todos = List.empty[ConceptInclusion]
    val subs = reasoner.subsBySubclass(ci.subclass)
    reasoner.negConjsByOperandLeft.getOrElse(ci.superclass, Map.empty).foreach {
      case (right, conj) =>
        if (subs(ConceptInclusion(ci.subclass, right))) {
          todos = ConceptInclusion(ci.subclass, conj) :: todos
        }
    }
    reasoner.negConjsByOperandRight.getOrElse(ci.superclass, Map.empty).foreach {
      case (left, conj) =>
        if (subs(ConceptInclusion(ci.subclass, left))) {
          todos = ConceptInclusion(ci.subclass, conj) :: todos
        }
    }
    reasoner.copy(todo = reasoner.todo.enqueue(todos))
  }

  // Different join order - much slower on Uberon
  private def `R+⨅subsFirst`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = {
    var todos = List.empty[ConceptInclusion]
    reasoner.subsBySubclass.getOrElse(ci.subclass, Nil).foreach { otherCI =>
      reasoner.negConjsByOperand.getOrElse(ci.superclass, Map.empty).get(otherCI.superclass).foreach { conj =>
        todos = ConceptInclusion(ci.subclass, conj) :: todos
      }
      reasoner.negConjsByOperand.getOrElse(otherCI.superclass, Map.empty).get(ci.superclass).foreach { conj =>
        todos = ConceptInclusion(ci.subclass, conj) :: todos
      }
    }
    reasoner.copy(todo = reasoner.todo.enqueue(todos))
  }

  private def `R-∃`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = ci match {
    case ConceptInclusion(c, ExistentialRestriction(role, filler)) => reasoner.copy(todo = reasoner.todo
      .enqueue(Link(c, role, filler)))
    case _ => reasoner
  }

  private def `R+∃`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(
    for {
      Link(e, r, _) <- reasoner.linksByTarget.getOrElse(ci.subclass, Nil)
      s <- reasoner.hier.getOrElse(r, Set.empty)
      f = ExistentialRestriction(s, ci.superclass)
      if reasoner.negExists(f)
    } yield ConceptInclusion(e, f)))

  private def `R⊑`(ci: ConceptInclusion, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(
    reasoner.concIncsBySubclass.getOrElse(ci.superclass, Nil).map(other => ConceptInclusion(ci.subclass, other.superclass))))

  private def `R⊥`(link: Link, reasoner: Reasoner): Reasoner = {
    if (reasoner.subs(ConceptInclusion(link.target, Bottom)))
      reasoner.copy(todo = reasoner.todo.enqueue(ConceptInclusion(link.subject, Bottom)))
    else reasoner
  }

  private def `R+∃`(link: Link, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(
    for {
      ConceptInclusion(_, superclass) <- reasoner.subsBySubclass.getOrElse(link.target, Nil)
      s <- reasoner.hier.getOrElse(link.role, Set.empty)
      f = ExistentialRestriction(s, superclass)
      if reasoner.negExists(f)
    } yield ConceptInclusion(link.subject, f)))

  private def `R∘`(link: Link, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(
    for {
      Link(_, r2, d) <- reasoner.linksBySubject.getOrElse(link.target, Nil)
      s1 <- reasoner.hier.getOrElse(link.role, Set.empty)
      s2 <- reasoner.hier.getOrElse(r2, Set.empty)
      s <- reasoner.roleComps.getOrElse((s1, s2), Set.empty)
    } yield Link(link.subject, s, d)))

  private def `R⤳`(link: Link, reasoner: Reasoner): Reasoner = reasoner.copy(todo = reasoner.todo.enqueue(link.target))

}