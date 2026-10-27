# Mateless for scalachess

When pawns, kings and bishops lock so that no series of legal moves can open the structure,
neither side can ever checkmate: the position is dead. FIDE ends the game there, drawn (5.2.2),
and a flag fall or a resignation in such a position is a draw as well (6.9, 5.1.2). scalachess
recognises insufficient material, but not a lock, so Lichess scores these games as wins.

This repository is the evidence for a scalachess change that ends a game, drawn, the moment it
locks: the lock detector of [Mateless](https://github.com/cuneytinann/Mateless), 181 lines of
Scala, asked by `Position.autoDraw`. The change is three commits on scalachess 18.0.1 (`a498819`,
22 Oct 2026): [`in-game/patches`](in-game/patches), and the same commits on the `dead-position-lock`
branch of [cuneytinann/scalachess](https://github.com/cuneytinann/scalachess/tree/dead-position-lock).

| | what | timeouts drawn (of 195,287, to Sep 2026) |
|---|---|---|
| scalachess 18.0.1 | insufficient material, and #821's one-move lookahead when a flag falls | 138,572 (71.0%) |
| **[in-game](in-game)** (proposed) | a lock of both sides ends the game, asked at halfmove clock 0 or 1 | **190,465 (97.5%)**, 52,034 of them before the flag fell |
| [a-lock](a-lock) (alternative) | the lock detector when a flag falls, at the position and in #821's lookahead | 190,719 (97.7%) |

The figures come from 195,287 rated Lichess games lost on time although the flagged side could
never be mated: chasolver's 192,457 up to 31 July 2026
([chasolver.org/unfair-games](https://chasolver.org/unfair-games)) and 2,830 from a scan of
Lichess's own databases for August and September. Every row is classified in Mateless's
[`data/`](https://github.com/cuneytinann/Mateless/tree/main/data).

## The problem

- **Timeouts.** Since #821 (merged in August 2026; on Lichess from 6 September, judging by the
  data) scalachess looks one move ahead when a flag falls, and the unfair timeouts fell from about
  65 a day to 19. Of the 460 still lost this way from 7 to 30 September, 422 (92%) were locks.
- **Dead games played on.** In August and September 8,718 drawn games went on after their position
  had become a lock for both sides, for a median of 14 plies (about 41 hours of clock time in
  all), until a repetition, an agreement or another rule ended them.
- **Resignations.** Of the 170 games resigned in August and September although the opponent could
  never mate, 95 were locks; Lichess scores every resignation as a loss.

## The change: `in-game`

1. **The detector**, `core/src/main/scala/mateless/Lock.scala` (181 lines, package
   `chess.mateless`): `Lock.cannotBeMated(position, flagged)`. A bitboard fixed point over the
   kings' reaches, the pawns that can never move and the squares where a pawn can be taken; it
   searches nothing and, apart from reading the position's lazy en passant square, generates no
   move and allocates nothing. Before the fixed point it fails at
   once with a knight, rook or queen on the board, two bishops of one side on squares of one
   colour, or an en passant square. Nothing calls it in this commit.
2. **The hook**, in `Position.scala`: one line changed and `lockedOut` added (3 lines of code, 5
   of comment):
   ```scala
   inline def autoDraw: Boolean = variant.autoDraw(this) || variant.specialDraw(this) || lockedOut

   def lockedOut: Boolean =
     history.halfMoveClock.value < 2 && (variant.standard || variant.fromPosition) &&
       mateless.Lock.cannotBeMated(this, Color.White) && mateless.Lock.cannotBeMated(this, Color.Black)
   ```
3. **The tests**, `test-kit/src/test/scala/LockTest.scala` (42 lines): Lichess timeouts that were
   dead positions, the clock rule, the pawn move that completes a lock, a king stepping out of a
   pawn's check into one, and no lock with a knight on the board or with a pawn that can still
   capture.

lila needs no code change: it reads `position.status` after every move, and `autoDraw` gives
`Status.Draw`. The game ends as a plain "Draw"; a reason of its own (a dead position) would be a
later change in lila. Like a position with insufficient material today, a locked position is not
`playable`: lila's FEN forms read with `Fen.read`, which sets the halfmove clock to 0, so they
reject every locked FEN as a starting position (games, tournaments, simuls, swiss, a study made
from a position), and lila-ws stores no cloud eval for one. Analysis and study boards are not
affected; chessops gives their moves.

**Why only at halfmove clock 0 or 1.** A lock can appear only after a capture or a pawn move: at
once (clock 0), or one move later (clock 1), once the en passant right of a double step has lapsed
or a king has stepped out of the check a pawn just gave. Any other move of a king or a bishop stays
inside the reaches the fixed point already holds, so it cannot complete a lock. The clock alone
therefore says when to ask, nothing has to be remembered from the move before, and in most plies
of a game the hook costs one comparison. Asking only one move later (clock 1) would be a little
cheaper, but it would miss the 3,110 timeouts whose flag fell right after the move that locked.
A game set up from a FEN is asked at its first position only if that position's halfmove clock is
0 or 1.

**Cost.** lila evaluates `autoDraw` after every move. With the hook (JVM 21, medians):
10.1 → 16.9 ns on a mix of positions from games' openings, 13.8 → 23.9 ns on a mix from their
endgames; on endgame positions at halfmove clock 0 or 1, where the detector runs, 17.9 → 73.9 and
21.6 → 85.3 ns. The detector itself, per call over 374,739 positions of real games and the 50,970
locks: about 31–35 ns with a knight, rook or queen on the board (it returns at its first test) and
about 150–190 ns on a lock. A rough loop over three positions already in the cache gives ~3.5 and
~65–72 ns; the difference is most likely the fetching of each position from memory.

**What it leaves.** Of the 195,287 timeouts, 4,822: 4,532 forced lines of more than one move (a
short search would be needed, Mateless's Domino), 237 kings that would have stepped out of a pawn's
check only after the flag, 16 locks of one side only (not dead: the flagged side could still mate,
a question for the flag), 36 that nothing here proves, and one forced capture of a rook after the
flag.

## How sure

The detector is sound, not complete: a structure it calls locked can never be opened, but it does
not recognise every dead position.

- `Lock.scala` is a port of the algorithm of Mateless's `src/lock.js` (the same sets and tests,
  not a line-for-line translation). On 3,643,939 positions it
  gives `mateless.js`'s answer for both sides on every one, and with the hook `autoDraw` is
  today's, or the clock at 0 or 1 and the lock for both sides, without exception (`check/ingame`,
  `check/lock`).
- Mateless's own evidence ([RESULTS.md](https://github.com/cuneytinann/Mateless/blob/main/docs/RESULTS.md#4-soundness)):
  no false draw against chasolver's labels (68,950 positions, both sides); 23,001 of 23,001 fuzzed
  lock certificates unwinnable by chasolver's full search; and from every lock in the data the
  reachable positions enumerated (up to 200,000 each) with no position breaking what the
  certificate claims: 50,970 locks up to July (271.8 million positions, 50,692 enumerated
  completely) and 1,175 in August and September (5.8 million, 1,171 completely).
- The August and September scan checked 24,044 "cannot mate" verdicts of Mateless's whole ladder
  (material, lock, Domino) with an independent search of the reachable positions: no mate in any;
  23,310 enumerated completely, 734 stopped at a cap of 1 million positions.
- scalachess's own expectations: `AutodrawTest`'s and `LockTest`'s hold (24 cases,
  `check/ingame`). The branch's CI runs scalachess's checks:
  [`sbt testKit/test`, `sbt compile`, `sbt check`](https://github.com/cuneytinann/scalachess/actions?query=branch%3Adead-position-lock).

## The alternative: `a-lock`, when a flag falls

- `core/src/main/scala/mateless/Lock.scala`: the same file.
- The hook (`Position.opponentHasInsufficientMaterial`, 9 lines): the lock detector at the position
  itself (not in a position without legal moves: the game is over), and in the positions of #821's
  lookahead. Standard and From Position only.
- `LockTest` (37 lines).

It draws 254 timeouts more than `in-game` (190,719): of the 52,147 it adds to today's, 51,909 are
locks at the position itself, 16 of them locks of one side only, and 238 come through the
lookahead (237 kings stepping out of a pawn's check into a lock, one lock after a forced capture of
a rook). What it leaves: 4,532 forced lines of more than one move and the 36 unproven. But it acts
only when a flag falls: a dead game is still played on, and a resignation in it is still a loss.
`in-game` follows FIDE 5.2.2 instead; the two can also be combined.

Cost (JVM 21, ns per call, after warm-up, over the 50,970 locks and 374,739 positions of real
games): ~150–190 on a locked position (the whole fixed point), ~30–35 on positions of real games
(a knight, rook or queen on the board: it stops at once). It
runs only when a flag falls. Mateless's Rust engine: ~105 and ~9 ns.

## c-ladder (not proposed): the whole ladder

Material, lock and Domino on scalachess's own board, legal moves and positions
(`mateless/Static.scala`, `Domino.scala`, `Ladder.scala`), and the hook asking the ladder
(`opponentCanNeverMate`). Moves are tried in Mateless's order, so proofs, failures and node counts
are those of the JavaScript and Rust engines. A verdict when a flag falls costs ~1–2.5 µs;
`opponentHasInsufficientMaterial` with the hook ~0.9 µs on average (0.6 µs without). A game's two
flags ply by ply (`Flags`, with `rewind` for takebacks) cost ~6.5–7 µs per ply, most of it
scalachess's king-safety test per move (Mateless's own Rust engine: ~0.6 µs). It draws 195,251 of
the 195,287 timeouts (99.98%). Its sources stay here for the comparison tools (`tools/build.sh ... c`
and `ref`).

## Why not the material test

Mateless's material test and scalachess's `InsufficientMatingMaterial` are the same set: 0
differences on every combination of material (each side 0–2 pawns, knights, light and dark
bishops, queens, 0–1 rooks, either side to move: 944,784 questions) and on 1.27 million questions
from chasolver's positions, the timeouts and real games. Per question Mateless's is faster (20–45
against 37–66 ns: scalachess builds small lists on each call), but per ply scalachess's every-move
dead check, one test that stops at its first bitboard, is cheaper (17–20 ns) than asking each side
(37–42 ns, only after a capture or a pawn move). Both are tens of nanoseconds.

## Verification

| | |
|---|---|
| in-game, a: `Lock.scala` | the same answers as `mateless.js` on 3,643,939 positions (chasolver's two files, the timeouts, real games), both sides |
| in-game: `autoDraw` | today's, or (clock 0 or 1 and the lock for both sides), on the same positions; `AutodrawTest`'s and `LockTest`'s expectations hold (24 cases) |
| in-game: coverage | 190,465 of the 195,287 timeouts, 52,034 of them ended before the flag fell (51,893 lost on time today) |
| a: coverage | 190,719 of the 195,287 timeouts with the hook |
| a, c: scalachess's tests | the 22 standard-chess expectations on `opponentHasInsufficientMaterial` (`AutodrawTest`, `VariantTest`) hold with the hook |
| c: against the Rust engine | byte-identical, node counts included: chasolver's 68,950 positions, 119,921 + 192,457 rows, 4,000 + 24,392 + 222,006 games (flags ply by ply), 4,598 takebacks |

scalachess compiles with each patch set under its own flags (`-Wunused:all -Werror`), and the files
pass scalafmt 3.11.5 with scalachess's `.scalafmt.conf`. munit is not available where the patches
were built, so `LockTest` and `MatelessTest` were not run by munit there: their assertions were run
by `check/ingame/InGame.scala`, `check/lock/LockCases.scala` and
`check/ladder/MatelessTestCases.scala`. With sbt: `git am in-game/patches/*.patch && sbt testKit/test`.

Note for tools reading FENs into scalachess: `Fen.read` drops the halfmove clock;
`Fen.readWithMoveNumber` keeps it (the in-game hook and a game's flags read it).

## Layout

| | |
|---|---|
| `in-game/` | `Lock.scala` and the three commits (`patches/`): the detector, the hook in `autoDraw`, `LockTest` (proposed) |
| `a-lock/` | `Lock.scala` and the three commits (`patches/`): the detector, the hook at the flag, `LockTest` (alternative) |
| `c-ladder/` | `Static.scala`, `Domino.scala`, `Ladder.scala` and the three commits: the ladder, the hook, `MatelessTest` |
| `check/ingame/` | `InGame.scala` (`dead`, `time`, `cases`) and `fivefold_moves.txt` (AutodrawTest's fivefold game) |
| `check/lock/` | `LockCheck.scala` (`locks`, `coverage`, `rows`, `time`), `LockCases.scala`, `lock_ref.js` (the same answers from `mateless.js`) |
| `check/ladder/` | `Check.scala`: the Rust tool's `labels`, `layers`, `games` lines, timings, takebacks, and against today's scalachess `scalachess`, `bare`, `material`, `material-time`, `rows`; `TestKitCases.scala`, `MatelessTestCases.scala` |
| `tools/build.sh` | scalachess and its dependencies from source, without sbt or Maven Central: `build.sh <dir> ingame`, `a`, `c` or `ref` |

```sh
tools/build.sh /tmp/w ingame  # about ten minutes the first time; needs git, curl, Java 21
CP=$(cat /tmp/w/classpath-ingame.txt)
java -cp "$CP" ingame cases check/ingame/fivefold_moves.txt  # AutodrawTest's and LockTest's expectations
java -Xss16m -cp "$CP" ingame dead fens.txt > ingame.tsv       # per FEN: autoDraw with the patch
tools/build.sh /tmp/w a
CP=$(cat /tmp/w/classpath-a.txt)
java -cp "$CP" lockcases
java -Xss16m -cp "$CP" lockcheck coverage timeouts.csv        # chasolver's CSV of the timeouts
java -Xss16m -cp "$CP" lockcheck rows timeouts.csv > a.tsv      # per row: drawn with the patch?
java -Xss16m -cp "$CP" lockcheck locks fens.txt > scala.tsv && MATELESS=../Mateless/mateless.js node check/lock/lock_ref.js fens.txt > js.tsv && cmp js.tsv scala.tsv
```

The commits are authored by Cüneyt İnan. MIT License ([LICENSE](LICENSE)); scalachess itself is
MIT-licensed.
