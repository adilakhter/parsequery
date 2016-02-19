package parsec

import scala.annotation.tailrec

/**
 * Something potentially beautiful:
 *
 * Consider the usual `rep` function on parsers:
 *
 *    rep: Parser[T] => Parser[List[T]]
 *
 * We can abstract over the return type, as essentially we are
 * folding a parser into a list:
 *
 *    rep: Parser[T] => (z: R, combine: (R, T) => R) => Parser[R]
 *
 * In curried terms, this is a function that takes a parser, and
 * returns a late parser (of a list):
 *
 *    rep: Parser[T] => CPSListParser[T]
 *
 * The signature of CPSListParser is different from that of a usual CPSList:
 *
 *    CPSList[T] => forall R. (z: R, combine: (R, T) => R) => R
 *
 * Note the difference in the final return type.
 * But we also need to be able to call methods on parser on a rep(p). Therefore
 * `CPSListParser` has to be have parser operations too! In monadic terms:
 * `CPSListParser` is a cps list monad stacked on top of a parser monad.
 *
 * This should help us get amazing fold fusion goodness on parser combinators!
 *
 * inspired from:
 * https://github.com/manojo/staged-fold-fusion/blob/master/src/main/scala/barbedwire/CPSList.scala
 *
 * the type signature of foldLeft is
 *    def foldLeft[T, R](z: R, comb: (R, T) => T)(xs: List[T]) : R
 *
 */

trait RepetitionParsers extends Parsers {

  /**
   * a type alias for the combination function for
   * foldLeft
   * `T` is the type of elements that pass through the fold
   * `R` is the type that is eventually computed
   */
  type Combine[T, R] = (R, T) => R

  def rep[T](p: Parser[T]) = fromParser(p)

  /**
   * repeatedly parses `parser`, interspersed with the `sep` parser
   * we must bake this in as a specific `FoldParser`, because we want to
   * use the `combine` function for the first parse result as well.
   * TODO: could `sep` always be a `Parser[Unit]`?
   */
  def repsep[T, U](parser: Parser[T], sep: => Parser[U]): FoldParser[T] = new FoldParser[T] {

    def fold[R](z: R, combine: Combine[T, R]): Parser[R] = Parser { in =>

      /* The loop runs over the composed parser */
      @tailrec
      def loop(curIn: Input, curRes: R): ParseResult[R] = (sep ~> parser)(curIn) match {
        case Success(res, rest) => loop(rest, combine(curRes, res))
        /* The rest is where we started failing*/
        case Failure(_, _) => Success(curRes, curIn)
      }

      /**
       * We need to run `parser` once, for getting the first result
       */
      parser(in) match {
        case Success(res, rest) => loop(rest, combine(z, res))
        case Failure(_, _) => Success(z, in)
      }
    }

  }

  /**
   * the repetition parser yields a `R` which is the result type
   * of a `FoldParser`.
   *
   * We need to have a CPSList hanging around
   */

  /* create a `FoldParser` given a parser */
  def fromParser[T](parser: Parser[T]): FoldParser[T] = new FoldParser[T] {
    def fold[R](z: R, combine: Combine[T, R]): Parser[R] = Parser { in =>

      @tailrec
      def loop(curIn: Input, curRes: R): ParseResult[R] = parser(curIn) match {
        case Success(res, rest) => loop(rest, combine(curRes, res))

        /**
         * The rest is where we started failing
         */
        case Failure(_, _) => Success(curRes, curIn)
      }

      loop(in, z)
    }
  }

  /**
   * Just the usual fold parser
   */
  abstract class FoldParser[T] { self =>

    def fold[R](z: R, combine: Combine[T, R]): Parser[R]

    /**
     * map. Pretty nice, cause we can forward the map
     * function over to the underlying parser, it's exactly
     * the same!
     */
    def map[U](f: T => U) = new FoldParser[U] {
      def fold[R](z: R, combine: Combine[U, R]): Parser[R] = self.fold(
        z,
        (acc: R, elem: T) => combine(acc, f(elem))
      )
    }

    /**
     * filter
     */
    def filter(p: T => Boolean) = new FoldParser[T] {
      def fold[R](z: R, comb: Combine[T, R]) = self.fold(
        z,
        (acc: R, elem: T) => if (p(elem)) comb(acc, elem) else acc
      )
    }

    /**
     * flatMap. It is unclear what semantics this should have for now
     * let's implement it later
     */
    /*def flatMap[U](f: T => CPSList[U, R]) = new FoldParser[U, R] {

      def fold(z: R, comb: Combine[U, R]) = self.fold(
        z,
        (acc: R, elem: T) => {
          val nestedList = f(elem)
          nestedList.fold(acc, comb)
        }
      )
    }*/

    /**
     * partition
     * This will create code what will run through the original fold twice
     * once for the positive predicate, once for the negative.
     *
     * see the following related post: http://manojo.github.io/2015/03/03/staged-foldleft-partition/
     */
    def partition(p: T => Boolean): (FoldParser[T], FoldParser[T]) = {
      val trues = this filter p
      val falses = this filter (a => !p(a))
      (trues, falses)
    }

    /**
     * partition, that produces a CPSList over `Either` instead of
     * two `CPSList`s. The important thing is to keep the one
     * CPSList abstraction.
     * This can be rewritten using `map`.
     * see the following related post: http://manojo.github.io/2015/03/12/staged-foldleft-groupby/
     */
    def partitionBis(p: T => Boolean) =
      this map (elem => if (p(elem)) Left(elem) else Right(elem))

    /**
     * groupWith
     * takes a function which computes some grouping property
     * does not create groups just yet, just propagates key-value pairs
     *
     * can be rewritten using `map`.
     * see the following related post: http://manojo.github.io/2015/03/12/staged-foldleft-groupby/
     */
    def groupWith[K](f: T => K): FoldParser[(K, T)] =
      this map (elem => (f(elem), elem))

    /**
     * utility functions that make it easier to write fold-like functions
     */
    def toListParser: Parser[List[T]] = {
      import scala.collection.mutable.ListBuffer
      self.fold[ListBuffer[T]](
        ListBuffer.empty[T],
        (acc: ListBuffer[T], t: T) => acc :+ t
      ).map(_.toList)
    }

    def toSkipper: Parser[Unit] = self.fold((), (acc: Unit, _) => acc)
    def toLength: Parser[Int] = self.fold(0, (acc: Int, _) => acc + 1)

  }

  /**
   * ops on folding chars
   */
  implicit class CharFoldOps(cFold: FoldParser[Char]) {

    def toStringParser: Parser[String] = {
      import scala.collection.mutable.StringBuilder
      cFold.fold[StringBuilder](
        StringBuilder.newBuilder,
        (acc: StringBuilder, c: Char) => acc append c
      ).map(_.toString)
    }
  }

}
