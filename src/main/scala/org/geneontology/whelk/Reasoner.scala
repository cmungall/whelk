package org.geneontology.whelk

import BuiltIn._
import scalaz._
import scalaz.Scalaz._

final case class ReasonerState(
  hier:                                   Map[Role, Set[Role]], // initial
  hierComps:                              Map[Role, Map[Role, List[Role]]], // initial
  assertions:                             List[ConceptInclusion],
  todo:                                   List[QueueExpression],
  topOccursNegatively:                    Boolean,
  inits:                                  Set[Concept], // closure
  assertedConceptInclusionsBySubclass:    Map[Concept, List[ConceptInclusion]],
  closureSubsBySuperclass:                Map[Concept, Set[Concept]],
  closureSubsBySubclass:                  Map[Concept, Set[Concept]],
  assertedNegConjs:                       Set[Conjunction],
  assertedNegConjsByOperandRight:         Map[Concept, List[Conjunction]],
  conjunctionsBySubclassesOfRightOperand: Map[Concept, Map[Concept, Set[Conjunction]]], // Map[subclassOfRightOperand, Map[leftOperand, Conjunction]]
  linksBySubject:                         Map[Concept, Map[Role, Set[Concept]]],
  linksByTarget:                          Map[Concept, Map[Role, List[Concept]]],
  negExistsMapByConcept:                  Map[Concept, Set[ExistentialRestriction]],
  propagations:                           Map[Concept, Map[Role, List[ExistentialRestriction]]]) {

  def subs: Set[ConceptInclusion] = closureSubsBySuperclass.flatMap {
    case (superclass, subclasses) =>
      subclasses.map(ConceptInclusion(_, superclass))
  }.toSet

}

object ReasonerState {

  val empty: ReasonerState = ReasonerState(Map.empty, Map.empty, Nil, Nil, false, Set.empty, Map.empty, Map.empty, Map.empty, Set.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)

}

object Reasoner {

  def assert(axioms: Set[Axiom]): ReasonerState = {
    import scalaz.syntax.semigroup._
    val allRoles = axioms.flatMap(_.signature).collect { case role: Role => role }
    val allRoleInclusions = axioms.collect { case ri: RoleInclusion => ri }
    val hier: Map[Role, Set[Role]] = saturateRoles(allRoleInclusions) |+| allRoles.map(r => r -> Set(r)).toMap
    val hierComps = indexRoleCompositions(hier, axioms.collect { case rc: RoleComposition => rc })
    val concIncs = axioms.collect { case ci: ConceptInclusion => ci }
    assert(concIncs, ReasonerState.empty.copy(hier = hier, hierComps = hierComps))
  }

  def assert(axioms: Set[ConceptInclusion], reasoner: ReasonerState): ReasonerState = {
    val distinctConcepts = axioms.flatMap {
      case ConceptInclusion(subclass, superclass) => Set(subclass, superclass)
    }.flatMap(_.conceptSignature)
    val additionalAxioms = distinctConcepts.flatMap {
      case d @ Disjunction(_) => `R⊔`(d)
      case c @ Complement(_)  => `R¬`(c)
      case _                  => Set.empty[ConceptInclusion]
    }
    computeClosure(reasoner.copy(
      assertions = reasoner.assertions ::: axioms.toList,
      todo = reasoner.todo ::: additionalAxioms.toList ::: axioms.toList))
  }

  private def computeClosure(reasoner: ReasonerState): ReasonerState = {
    if (reasoner.assertions.nonEmpty) {
      val item :: todoAssertions = reasoner.assertions
      computeClosure(processAssertedConceptInclusion(item, reasoner.copy(assertions = todoAssertions)))
    } else if (reasoner.todo.nonEmpty) {
      val item :: todo = reasoner.todo
      computeClosure(process(item, reasoner.copy(todo = todo)))
    } else reasoner
  }

  private def processAssertedConceptInclusion(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    val updated = reasoner.assertedConceptInclusionsBySubclass.updated(ci.subclass, (ci :: reasoner.assertedConceptInclusionsBySubclass.getOrElse(ci.subclass, Nil)))
    `R⊑left`(ci, `R+∃a`(ci, `R+⨅a`(ci, `R⊤left`(ci, reasoner.copy(assertedConceptInclusionsBySubclass = updated)))))
  }

  private def process(expression: QueueExpression, reasoner: ReasonerState): ReasonerState = {
    expression match {
      case concept: Concept => if (reasoner.inits(concept)) reasoner else
        `R⊤right`(concept, R0(concept, reasoner.copy(inits = reasoner.inits + concept)))
      case ci @ ConceptInclusion(subclass, superclass) =>
        val subs = reasoner.closureSubsBySuperclass.getOrElse(superclass, Set.empty)
        if (subs(subclass)) reasoner else {
          val closureSubsBySuperclass = reasoner.closureSubsBySuperclass.updated(superclass, (subs + subclass))
          val supers = reasoner.closureSubsBySubclass.getOrElse(subclass, Set.empty)
          val closureSubsBySubclass = reasoner.closureSubsBySubclass.updated(subclass, (supers + superclass))
          `R⊑right`(ci, `R+∃b-right`(ci, `R-∃`(ci, `R+⨅b-right`(ci, `R+⨅right`(ci, `R-⨅`(ci, `R⊥left`(ci, reasoner.copy(closureSubsBySuperclass = closureSubsBySuperclass, closureSubsBySubclass = closureSubsBySubclass))))))))
        }
      case `Sub+`(ci @ ConceptInclusion(subclass, superclass)) =>
        val subs = reasoner.closureSubsBySuperclass.getOrElse(superclass, Set.empty)
        if (subs(subclass)) reasoner else {
          val closureSubsBySuperclass = reasoner.closureSubsBySuperclass.updated(superclass, (subs + subclass))
          val supers = reasoner.closureSubsBySubclass.getOrElse(subclass, Set.empty)
          val closureSubsBySubclass = reasoner.closureSubsBySubclass.updated(subclass, (supers + superclass))
          `R⊑right`(ci, `R+∃b-right`(ci, `R+⨅b-right`(ci, `R+⨅right`(ci, `R⊥left`(ci, reasoner.copy(closureSubsBySuperclass = closureSubsBySuperclass, closureSubsBySubclass = closureSubsBySubclass))))))
        }
      case link @ Link(subject, role, target) =>
        val rolesToTargets = reasoner.linksBySubject.getOrElse(subject, Map.empty)
        val targetsSet = rolesToTargets.getOrElse(role, Set.empty[Concept])
        if (targetsSet(target)) reasoner else {
          val updatedTargetsSet = targetsSet + target
          val updatedRolesToTargets = rolesToTargets.updated(role, updatedTargetsSet)
          val linksBySubject = reasoner.linksBySubject.updated(subject, updatedRolesToTargets)
          val rolesToSubjects = reasoner.linksByTarget.getOrElse(target, Map.empty)
          val subjects = rolesToSubjects.getOrElse(role, Nil)
          val updatedSubjects = subject :: subjects
          val updatedRolesToSubjects = rolesToSubjects.updated(role, updatedSubjects)
          val linksByTarget = reasoner.linksByTarget.updated(target, updatedRolesToSubjects)
          `R⤳`(link, `R∘left`(link, `R∘right`(link, `R+∃right`(link, `R⊥right`(link, reasoner.copy(linksBySubject = linksBySubject, linksByTarget = linksByTarget))))))
        }
    }
  }

  private def R0(concept: Concept, reasoner: ReasonerState): ReasonerState =
    reasoner.copy(todo = ConceptInclusion(concept, concept) :: reasoner.todo)

  private def `R⊤left`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState =
    if (ci.subclass.signature(Top)) reasoner.copy(topOccursNegatively = true) else reasoner

  private def `R⊤right`(concept: Concept, reasoner: ReasonerState): ReasonerState =
    if (reasoner.topOccursNegatively) reasoner.copy(todo = ConceptInclusion(concept, Top) :: reasoner.todo)
    else reasoner

  private def `R⊑left`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    reasoner.closureSubsBySuperclass.getOrElse(ci.subclass, Set.empty).foreach { other =>
      todo = ConceptInclusion(other, ci.superclass) :: todo
    }
    reasoner.copy(todo = todo)
  }

  private def `R⊑right`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    reasoner.assertedConceptInclusionsBySubclass.getOrElse(ci.superclass, Nil).foreach { other =>
      todo = ConceptInclusion(ci.subclass, other.superclass) :: todo
    }
    reasoner.copy(todo = todo)
  }

  private def `R⊥left`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    if (ci.superclass == Bottom) {
      for {
        (role, subjects) <- reasoner.linksByTarget.getOrElse(ci.subclass, Map.empty)
        subject <- subjects
      } todo = ConceptInclusion(subject, Bottom) :: todo
      reasoner.copy(todo = todo)
    } else reasoner
  }

  private def `R⊥right`(link: Link, reasoner: ReasonerState): ReasonerState = {
    if (reasoner.closureSubsBySuperclass.getOrElse(Bottom, Set.empty)(link.target))
      reasoner.copy(todo = ConceptInclusion(link.subject, Bottom) :: reasoner.todo)
    else reasoner
  }

  private def `R-⨅`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = ci match {
    case ConceptInclusion(sub, Conjunction(left, right)) => reasoner.copy(todo = ConceptInclusion(sub, left) :: ConceptInclusion(sub, right) :: reasoner.todo)
    case _ => reasoner
  }

  private def `R+⨅a`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    val newNegativeConjunctions = ci.subclass.conceptSignature.collect { case conj: Conjunction => conj }.filterNot(reasoner.assertedNegConjs)
    val newAssertedNegConjs = reasoner.assertedNegConjs ++ newNegativeConjunctions
    val newNegConjsByOperandRight = newNegativeConjunctions.foldLeft(reasoner.assertedNegConjsByOperandRight) {
      case (acc, c @ Conjunction(left, right)) =>
        val updated = c :: acc.getOrElse(right, Nil)
        acc.updated(right, updated)
    }
    `R+⨅b-left`(newNegativeConjunctions, reasoner.copy(assertedNegConjs = newAssertedNegConjs, assertedNegConjsByOperandRight = newNegConjsByOperandRight))
  }

  private def mergeConjunctionIndexes(existing: Map[Concept, Map[Concept, Set[Conjunction]]], toAdd: Iterable[(Concept, Conjunction)]): Map[Concept, Map[Concept, Set[Conjunction]]] =
    toAdd.foldLeft(existing) {
      case (acc, (concept, conjunction)) =>
        val conjunctionsByLeft = acc.getOrElse(concept, Map.empty)
        val newConjunctionsForThisLeft = conjunctionsByLeft.getOrElse(conjunction.left, Set.empty) + conjunction
        val newValue = conjunctionsByLeft.updated(conjunction.left, newConjunctionsForThisLeft)
        acc.updated(concept, newValue)
    }

  private def `R+⨅b-left`(newNegativeConjunctions: Iterable[Conjunction], reasoner: ReasonerState): ReasonerState = {
    val newSubclassesAndConjunctions = for {
      conjunction <- newNegativeConjunctions
      cs <- reasoner.closureSubsBySuperclass.get(conjunction.right).toIterable
      c <- cs
    } yield c -> conjunction
    val newIndex = mergeConjunctionIndexes(reasoner.conjunctionsBySubclassesOfRightOperand, newSubclassesAndConjunctions)
    `R+⨅left`(newSubclassesAndConjunctions, reasoner.copy(conjunctionsBySubclassesOfRightOperand = newIndex))
  }

  private def `R+⨅b-right`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = { //SLOW
    val newSubclassesAndConjunctions = for {
      conjunctions <- reasoner.assertedNegConjsByOperandRight.get(ci.superclass).toIterable
      conjunction <- conjunctions
    } yield ci.subclass -> conjunction
    val newIndex = mergeConjunctionIndexes(reasoner.conjunctionsBySubclassesOfRightOperand, newSubclassesAndConjunctions)
    `R+⨅left`(newSubclassesAndConjunctions, reasoner.copy(conjunctionsBySubclassesOfRightOperand = newIndex))
  }

  private def `R+⨅left`(subclassesAndConjunctions: Iterable[(Concept, Conjunction)], reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      (c, conjunction) <- subclassesAndConjunctions
      subclasses <- reasoner.closureSubsBySuperclass.get(conjunction.left)
      if subclasses(c)
    } todo = `Sub+`(ConceptInclusion(c, conjunction)) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R+⨅right`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      conjunctionsByLeft <- reasoner.conjunctionsBySubclassesOfRightOperand.get(ci.subclass)
      conjunctions <- conjunctionsByLeft.get(ci.superclass)
      conjunction <- conjunctions
    } todo = `Sub+`(ConceptInclusion(ci.subclass, conjunction)) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R-∃`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = ci match {
    case ConceptInclusion(c, ExistentialRestriction(role, filler)) => reasoner.copy(todo = Link(c, role, filler) :: reasoner.todo)
    case _ => reasoner
  }

  private def `R+∃a`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    val newNegativeExistentials = ci.subclass.conceptSignature.collect { case er: ExistentialRestriction => er }
    val negExistsMapByConcept = newNegativeExistentials.foldLeft(reasoner.negExistsMapByConcept) { (acc, er) =>
      val updated = acc.getOrElse(er.concept, Set.empty) + er
      acc.updated(er.concept, updated)
    }
    `R+∃b-left`(newNegativeExistentials, reasoner.copy(negExistsMapByConcept = negExistsMapByConcept))
  }

  private def `R+∃b-left`(newNegativeExistentials: Iterable[ExistentialRestriction], reasoner: ReasonerState): ReasonerState = {
    val newPropagations = for {
      er <- newNegativeExistentials
      subclasses <- reasoner.closureSubsBySuperclass.get(er.concept).toIterable
      subclass <- subclasses
    } yield subclass -> er
    val propagations = newPropagations.foldLeft(reasoner.propagations) {
      case (acc, (concept, er)) =>
        val current = acc.getOrElse(er.concept, Map.empty)
        val newList = er :: current.getOrElse(er.role, Nil)
        acc.updated(er.concept, (current.updated(er.role, newList)))
    }
    `R+∃left`(newPropagations, reasoner.copy(propagations = propagations))
  }

  private def `R+∃b-right`(ci: ConceptInclusion, reasoner: ReasonerState): ReasonerState = {
    val newPropagations = for {
      ers <- reasoner.negExistsMapByConcept.get(ci.superclass).toIterable
      er <- ers
    } yield ci.subclass -> er
    val propagations = newPropagations.foldLeft(reasoner.propagations) {
      case (acc, (concept, er)) =>
        val current = acc.getOrElse(concept, Map.empty)
        val newList = er :: current.getOrElse(er.role, Nil)
        acc.updated(concept, (current.updated(er.role, newList)))
    }
    `R+∃left`(newPropagations, reasoner.copy(propagations = propagations))
  }

  private def `R+∃left`(newPropagations: Iterable[(Concept, ExistentialRestriction)], reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      (concept, er) <- newPropagations
      (role, subjects) <- reasoner.linksByTarget.getOrElse(concept, Map.empty)
      if reasoner.hier(role)(er.role)
      subject <- subjects
    } todo = `Sub+`(ConceptInclusion(subject, er)) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R+∃right`(link: Link, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      roleToER <- reasoner.propagations.get(link.target).toIterable
      s <- reasoner.hier(link.role)
      fs <- roleToER.get(s)
      f <- fs
    } todo = `Sub+`(ConceptInclusion(link.subject, f)) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R∘left`(link: Link, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      (r1, es) <- reasoner.linksByTarget.getOrElse(link.subject, Map.empty)
      r1s <- reasoner.hierComps.get(r1)
      ss <- r1s.get(link.role)
      s <- ss
      e <- es
    } todo = Link(e, s, link.target) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R∘right`(link: Link, reasoner: ReasonerState): ReasonerState = {
    var todo = reasoner.todo
    for {
      (r2, targets) <- reasoner.linksBySubject.getOrElse(link.target, Map.empty)
      r2s <- reasoner.hierComps.get(link.role)
      ss <- r2s.get(r2)
      s <- ss
      d <- targets
    } todo = Link(link.subject, s, d) :: todo
    reasoner.copy(todo = todo)
  }

  private def `R⤳`(link: Link, reasoner: ReasonerState): ReasonerState = reasoner.copy(todo = link.target :: reasoner.todo)

  private def `R⊔`(d: Disjunction): Set[ConceptInclusion] = d.operands.map(o => ConceptInclusion(o, d))

  private def `R¬`(c: Complement): Set[ConceptInclusion] = Set(ConceptInclusion(Conjunction(c.concept, c), Bottom))

  private def saturateRoles(roleInclusions: Set[RoleInclusion]): Map[Role, Set[Role]] = { //FIXME can do this better?
    val subToSuper = roleInclusions.groupBy(_.subproperty).map { case (sub, ri) => sub -> ri.map(_.superproperty) }
    def allSupers(role: Role): Set[Role] = for {
      superProp <- subToSuper.getOrElse(role, Set.empty)
      superSuperProp <- allSupers(superProp) + superProp
    } yield superSuperProp
    subToSuper.keys.map(role => role -> allSupers(role)).toMap
  }

  private def indexRoleCompositions(hier: Map[Role, Set[Role]], chains: Set[RoleComposition]): Map[Role, Map[Role, List[Role]]] = {
    val roleComps = chains.groupBy(rc => (rc.first, rc.second)).map {
      case (key, ris) =>
        key -> ris.map(_.superproperty)
    }
    val hierCompsTuples = (for {
      (r1, s1s) <- hier
      s1 <- s1s
      (r2, s2s) <- hier
      s2 <- s2s
      s <- roleComps.getOrElse((s1, s2), Set.empty)
    } yield (r1, r2, s)).toSet
    val hierCompsRemove = for {
      (r1, r2, s) <- hierCompsTuples
      superS <- hier(s)
      if superS != s
      if hierCompsTuples((r1, r2, superS))
    } yield (r1, r2, superS)
    val hierComps = (hierCompsTuples -- hierCompsRemove).groupBy(_._1).map {
      case (r1, values) => r1 -> (values.map {
        case (r1, r2, s) => (r2, s)
      }).groupBy(_._1).map {
        case (r2, ss) => r2 -> ss.map(_._2).toList
      }
    }
    hierComps
  }

}