package parsec

/**
 * A trait for parsing characters
 */
trait CharParsers extends Parsers with RepetitionParsers {

  type Elem = Char

  /**
   * some other handy parsers
   */
  def letter: Parser[Char] = acceptIf(_.isLetter)
  def digit: Parser[Char] = acceptIf(_.isDigit)
  def digit2Int: Parser[Int] = digit map { c => (c - '0').toInt }
  def singleSpace: Parser[Char] = accept(' ')
  def comma = accept(',')
  def CRLF = accept('\n')

  def letters = repFold(letter)
  def digits = repFold(digit)

  /**
   * at least one digit should match
   */
  def number: Parser[Int] = digit2Int flatMap { d =>
    repFold(digit2Int).fold[Int](d, (acc, n) => acc * 10 + n)
  }

  def negNumber: Parser[Int] = (opt(accept('-')) ~ number) map {
    case (Some(_), num) => -num
    case (_, num) => num
  }

  /**
   * parses a double, but specialised version
   * we find the interval that the double spawns
   * and call .toDouble on the subSequence for
   * that interval. We catch the exception to propagate a failure
   *
   * -?(\d+(\.\d*)?|\d*\.\d+)([eE][+-]?\d+)?[fFdD]?
   * TODO, rethink implementation and make it better!
   */
  def double: Parser[Double] = Parser { in =>
    import scala.annotation.tailrec

    var curIn = in
    if (!curIn.atEnd && curIn.first == '-') { curIn = curIn.rest }
    while (!curIn.atEnd && curIn.first.isDigit) { curIn = curIn.rest }
    if (!curIn.atEnd && curIn.first == '.') { curIn = curIn.rest }
    while (!curIn.atEnd && curIn.first.isDigit) { curIn = curIn.rest }
    if (!curIn.atEnd && curIn.first == 'e') {
      curIn = curIn.rest
      if (!curIn.atEnd && (curIn.first == '+' || curIn.first == '-')) { curIn = curIn.rest }
      while (!curIn.atEnd && curIn.first.isDigit) { curIn = curIn.rest }
    }

    if (curIn.pos == in.pos) Failure("could not parse a double", in)
    else try {
      val res = in.source.subSequence(in.pos, curIn.pos).toString.toDouble
      Success(res, curIn)
    } catch {
      case _: Throwable => Failure("could not parse a double", in)
    }
  }

  def ws = repFold(singleSpace | CRLF)
  val ignoreWs = ws.toSkipper

  /**
   * parses a string passed as a parameter
   * returns the string in question if successful
   */
  def accept(s: String): Parser[String] = accept(s.toArray).map(_ => s)

  def accept(str: Array[Char]): Parser[Array[Char]] = Parser { in =>
    import scala.annotation.tailrec

    @tailrec
    def loop(curIn: Input, curIdx: Int): ParseResult[Array[Char]] = {
      if (curIdx >= str.length) Success(str, curIn)
      else if (curIn.atEnd) Failure(s"failed to match $str, went out of bounds", in)
      else if (curIn.first != str(curIdx)) Failure(s"failed to match $str", in)
      else loop(curIn.rest, curIdx + 1)
    }

    loop(in, 0)
  }

  /**
   * __recognizes__ a string passed as a parameter
   * returns unit, as the success of the parse is captured in
   * the type of ParseResult already
   */
  def recognize(s: String): Parser[Unit] = Parser { in =>
    import scala.annotation.tailrec

    val arr = s.toArray

    @tailrec
    def loop(curIn: Input, curIdx: Int): ParseResult[Unit] = {
      if (curIn.atEnd) Failure(s"failed to match $s, went out of bounds", in)
      if (curIdx >= arr.length) Success((), curIn)
      else if (curIn.first != arr(curIdx)) Failure(s"failed to match $s", in)
      else loop(curIn.rest, curIdx + 1)
    }

    loop(in, 0)
  }

  /**
   * parses a simple string literal, does not handle "weird" characters yet.
   * weird characters include much of unicode (stuff that starts with \\u???)
   */
  def stringLiteral: Parser[String] = (accept('"') ~>
    repFold(
      (accept('\\') ~> acceptIf(_ => true)) |
      acceptIf(_ != '"')
    ).toStringParser
  <~ accept('"'))


  /**
   * surrounds any parser with a whitespace ignoring parser
   * left and right
   */
  def skipWs[T](p: Parser[T]): Parser[T] = //ignoreWs ~> p <~ ignoreWs
    parseMany[T](List(
      (ignoreWs, true),
      (p, false),
      (ignoreWs, true)
    ))
}
