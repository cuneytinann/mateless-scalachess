# Mateless for scalachess

When pawns, kings and bishops lock so that no series of legal moves can open the structure,
neither side can ever checkmate: the position is dead. FIDE ends the game there, drawn (5.2.2),
and a flag fall or a resignation in such a position is a draw as well (6.9, 5.1.2). scalachess
recognises insufficient material, but not a lock, so Lichess scores these games as wins.

This repository is the evidence for the change proposed in
[lichess-org/scalachess#916](https://github.com/lichess-org/scalachess/pull/916): the lock
detector of [Mateless](https://github.com/cuneytinann/Mateless), 181 lines of Scala, asked by
scalachess in two places.

- **In play** (`Position.autoDraw`): a lock of both sides ends the game, drawn, the moment it
  appears. It is asked at halfmove clock 0 or 1 only.
- **When a game ends on time or by resignation** (`opponentHasInsufficientMaterial` and
  `playerHasInsufficientMaterial`): a side that is locked out of being mated cannot lose. #821's
  one-move lookahead asks `playerHasInsufficientMaterial`, so it also sees a lock one move away.
- **Standard, Chess960 and From Position.** A lock has no rook, so there is no castling, and a lock
  in Chess960 is the same as in Standard.

#916 is five commits on scalachess 18.1.0 (master at `176d2f7`, 17 November 2026):
[`proposed/patches`](proposed/patches), and the same commits on the `dead-position` branch of
[cuneytinann/scalachess](https://github.com/cuneytinann/scalachess/tree/dead-position). It replaced
[#907](https://github.com/lichess-org/scalachess/pull/907) (on 18.0.1, in play only). The two
halves were first built and measured apart here, as [`in-game`](in-game) and [`a-lock`](a-lock);
the detector, `Lock.scala`, is the same file in all three, byte for byte.

| | what | timeouts drawn (of 195,287, to Sep 2026) |
|---|---|---|
| scalachess today (18.1.0) | insufficient material, and #821's one-move lookahead when a flag falls | 138,572 (71.0%) |
| [in-game](in-game) | a lock of both sides ends the game, asked at halfmove clock 0 or 1 | 190,465 (97.5%), 52,034 of them before the flag fell |
| [a-lock](a-lock) | the lock detector when a flag falls, at the position and in #821's lookahead | 190,719 (97.7%) |
| **[#916](proposed)** (proposed) | both | **190,719 (97.7%)**, 52,034 of them before the flag fell |

The figures come from 195,287 rated Lichess games lost on time although the flagged side could
never be mated: chasolver's 192,457 up to 31 July 2026
([chasolver.org/unfair-games](https://chasolver.org/unfair-games)) and 2,830 from a scan of
Lichess's own databases for August and September. Every row is classified in Mateless's
[`data/`](https://github.com/cuneytinann/Mateless/tree/main/data).

chasolver's list has grown since: on 19 November 2026 it held 204,484 rows up to 31 October 2026,
and Mateless's lock detector proves 52,609 of them (25.7%). In October 2026, 559 of the 594 such
games (94%) ended in a lock. The table above has not been recomputed on the new list.

## The problem

- **Timeouts.** Since #821 (merged in August 2026; on Lichess from 6 September, judging by the
  data) scalachess looks one move ahead when a flag falls, and the unfair timeouts fell from about
  65 a day to 19. Of the 460 still lost this way from 7 to 30 September, 422 (92%) were locks.
- **Dead games played on.** In August and September 8,718 drawn games went on after their position
  had become a lock for both sides, for a median of 14 plies (about 41 hours of clock time in
  all), until a repetition, an agreement or another rule ended them. In October: 4,634, a median
  of 15 plies.
- **Resignations.** Of the 170 games resigned in August and September although the opponent could
  never mate, 95 were locks; Lichess scores every resignation as a loss.
- **An example.** [lichess.org/JWwQFgmD](https://lichess.org/JWwQFgmD) (rapid 10+0, 8 September
  2026): for the last 38 moves only the kings moved, in a locked position; then Black lost on time.

## The change: #916

1. **The detector**, `core/src/main/scala/mateless/Lock.scala` (181 lines, package
   `chess.mateless`): `Lock.cannotBeMated(position, flagged)`. A bitboard fixed point over the
   kings' reaches, the pawns that can never move and the squares where a pawn can be taken; it
   searches nothing and, apart from reading the position's lazy en passant square, generates no
   move and allocates nothing. Before the fixed point it fails at once with a knight, rook or
   queen on the board, two bishops of one side on squares of one colour, or an en passant square.
   Nothing calls it in this commit.
2. **The hooks**, in `Position.scala` (9 lines of code, 2 of them changed, and 5 of comment):
   ```scala
   inline def autoDraw: Boolean = variant.autoDraw(this) || variant.specialDraw(this) || lockedOut

   def lockedOut: Boolean =
     history.halfMoveClock.value < 2 && lockedFor(Color.White) && lockedFor(Color.Black)

   private inline def lockable: Boolean = variant.standard || variant.chess960 || variant.fromPosition

   private def lockedFor(flagged: Color): Boolean =
     lockable && mateless.Lock.cannotBeMated(this, flagged)

   def opponentHasInsufficientMaterial: Boolean =
     variant.opponentHasInsufficientMaterial(this) ||
       (lockedFor(color) && legalMoves.nonEmpty) ||
       // ... today's test, unchanged

   def playerHasInsufficientMaterial: Boolean =
     variant.playerHasInsufficientMaterial(this) || lockedFor(!color)
   ```
3. **The tests**, `test-kit/src/test/scala/LockTest.scala` (83 lines, 17 tests). In play: three
   Lichess timeouts that were dead positions, the clock rule, the pawn move that completes a lock,
   a king stepping out of a pawn's check into one, Chess960, no question in other variants, and no
   lock with a knight on the board or with a pawn that can still capture. When a flag falls: a lock
   at any halfmove clock, a lock of the flagged side only (not dead), every move stepping out of a
   pawn's check into a lock, an only move that takes the last rook into a lock, and no lock. On a
   resignation: the locked side cannot lose, on either turn; the other side still can.

## In lila

No code change; lila already asks these functions:

- when a flag falls (`Finisher.outOfTime`): drawn if the flagged side is locked;
- on a resignation or a draw offer (`RoundGame.cannotLose`): a locked side ends the game drawn,
  shown as "Draw claimed • Insufficient material", as with insufficient material today;
- on a victory claim after the opponent left (`ResignForce`): drawn if the opponent is locked.

A dead position ends the game at once: lila reads `position.status` after every move, and
`autoDraw` gives `Status.Draw`, shown as a plain "Draw". A "Dead position" label would be a later
change in lila.

Side effects: like a position with insufficient material today, a locked position is not
`playable`. lila's FEN forms read with `Fen.read`, which sets the halfmove clock to 0, so they
reject every locked FEN as a starting position (games, tournaments, simuls, swiss, a study made
from a position), and lila-ws stores no cloud eval for one. Analysis and study boards are not
affected; chessops gives their moves.

## Why only at halfmove clock 0 or 1

A lock can appear only after a capture or a pawn move: at once (clock 0), or one move later
(clock 1), once the en passant right of a double step has lapsed or a king has stepped out of the
check a pawn just gave. Any other move of a king or a bishop stays inside the reaches the fixed
point already holds, so it cannot complete a lock. The clock alone therefore says when to ask,
nothing has to be remembered from the move before, and in most plies of a game the hook costs one
comparison. Asking only one move later (clock 1) would be a little cheaper, but it would miss the
3,110 timeouts whose flag fell right after the move that locked. A game set up from a FEN is asked
at its first position only if that position's halfmove clock is 0 or 1.

## Cost

lila evaluates `autoDraw` after every move. With the hook (JVM 21, medians): 10.1 → 16.9 ns on a
mix of positions from games' openings, 13.8 → 23.9 ns on a mix from their endgames; on endgame
positions where the detector runs, 17.9 → 73.9 ns at halfmove clock 0 and 21.6 → 85.3 ns at
clock 1. The other two functions run only when a flag falls, or when a player resigns, offers a draw
or claims a win.

The detector itself, per call over 374,739 positions of real games and the 50,970 locks: about
31–35 ns with a knight, rook or queen on the board (it returns at its first test) and about 150–190
ns on a lock. A rough loop over three positions already in the cache gives ~3.5 and ~65–72 ns; the
difference is most likely the fetching of each position from memory. Mateless's Rust engine: ~9
and ~105 ns.

## What it leaves

Of the 195,287 timeouts, #916 leaves 4,568: 4,532 forced lines of more than one move (a short
search would be needed, Mateless's Domino) and 36 that nothing here proves. In play alone
(`in-game`) would leave 254 more, which #916 draws when the flag falls: 237 kings that step out of
a pawn's check only after the flag, 16 locks of one side only (not dead: the flagged side could
still mate, a question for the flag) and one forced capture of a rook after the flag.

## How sure

The detector is sound, not complete: a structure it calls locked can never be opened, but it does
not recognise every dead position. There is no written proof yet; the evidence:

- `Lock.scala` is a port of the algorithm of Mateless's `src/lock.js` (the same sets and tests,
  not a line-for-line translation). On 3,643,939 positions it gives `mateless.js`'s answer for both
  sides on every one (`check/lock`). With `in-game`'s hook, `autoDraw` is today's, or the clock at
  0 or 1 and the lock for both sides, without exception (`check/ingame`); for Standard and From
  Position, #916's `autoDraw` is the same expression.
- Mateless's own evidence ([RESULTS.md](https://github.com/cuneytinann/Mateless/blob/main/docs/RESULTS.md#4-soundness)):
  no false draw against chasolver's labels (68,950 positions, both sides); 23,001 of 23,001 fuzzed
  lock certificates unwinnable by chasolver's full search; and from every lock in the data the
  reachable positions enumerated (up to 200,000 each) with no position breaking what the
  certificate claims: 50,970 locks up to July (271.8 million positions, 50,692 enumerated
  completely) and 1,175 in August and September (5.8 million, 1,171 completely).
- The August and September scan checked 24,044 "cannot mate" verdicts of Mateless's whole ladder
  (material, lock, Domino) with an independent search of the reachable positions: no mate in any;
  23,310 enumerated completely, 734 stopped at a cap of 1 million positions.
- scalachess's own expectations: #916's branch CI, all green (`LockTest`'s 17 tests among
  scalachess's): [run 44596451105](https://github.com/cuneytinann/scalachess/actions/runs/44596451105).

## Testing on lichess.org and a local lila

The screenshots were taken at `4c7d4b2`, the commit before the last one. The last commit moves the
lookahead's lock check into `playerHasInsufficientMaterial` and adds the resignation on the
opponent's turn; `LockTest` covers both.

`LockTest` on my machine, in the lila-docker container (15 tests at `4c7d4b2`):

<img width="1535" height="862" alt="LockTest passing in the lila-docker container" src="https://github.com/user-attachments/assets/1b2bd26b-b548-402d-ba57-a08d9cf3ebfd" />

**Before**, on lichess.org, against the computer:

- [lichess.org/elwK6CjW](https://lichess.org/elwK6CjW): from
  `8/8/4k3/3p2p1/1p1P1pP1/1P6/5P2/3K4 w - - 0 91`, the move 1. f3 locks both sides. I let my clock
  run out and lost.

  <img width="1535" height="862" alt="The position before f3" src="https://github.com/user-attachments/assets/80d3f5c7-c2c1-4d1c-8c6f-8d12567ff43c" />
  <img width="1535" height="862" alt="After f3, the game goes on" src="https://github.com/user-attachments/assets/ada2b53f-cd79-4db9-9d79-d3b07a34f674" />
  <img width="1535" height="862" alt="Lost on time in the locked position" src="https://github.com/user-attachments/assets/48a0e083-b9c6-4509-8465-492551695fc2" />

- [lichess.org/0HX1U5L4](https://lichess.org/0HX1U5L4): from `8/1p1k4/4p3/1P1pP1p1/1p1P2Pp/1P1K3P/8/8 w - - 0 47`
  (the final position of vcaVIyhj), White is locked and Black is not. I let my clock run out and
  lost.

  <img width="1535" height="862" alt="White, locked out of being mated, loses on time" src="https://github.com/user-attachments/assets/97b59216-2d22-4bb6-9cad-2b390e6b85e1" />

**After**, on a local lila (lila-docker, lila master `63ce123`) with the branch published locally,
between two seeded users:

- The same f3 position is drawn as soon as f3 is played.

  <img width="1535" height="862" alt="Drawn when f3 locks the position" src="https://github.com/user-attachments/assets/0ba5e533-9e75-46a6-a01d-2d5eb93b17ef" />

- The vcaVIyhj position: White runs out of time, and the game is drawn.

  <img width="1535" height="862" alt="Drawn when the locked side runs out of time" src="https://github.com/user-attachments/assets/0f5420de-61c5-4237-a144-d87016582406" />

- White's only move takes the last rook and locks the position. White runs out of time, and the
  game is drawn: #821's lookahead now sees the lock. I couldn't reproduce this one against the
  computer on lichess.org, because it won't leave its rook there.

  <img width="1533" height="862" alt="Drawn when the only move takes the last rook into a lock" src="https://github.com/user-attachments/assets/202d3d10-31eb-43c5-a4e0-1886486349fb" />

- Three screenshots from #907's branch, where the check in play is the same: from
  `8/8/pk6/3p2p1/PK1P2Pp/7P/8/8 b - - 0 48`, the game goes on after 1...a5+ while the white king
  is in the pawn's check, and is drawn after 2. Kc3 steps out into a lock. With a white knight on
  e1, the game goes on.

  <img width="1533" height="862" alt="After a5+, the king in the pawn's check: the game goes on" src="https://github.com/user-attachments/assets/f7b815d6-6607-45ef-a1b6-209814e3c9dd" />
  <img width="1535" height="862" alt="Kc3 steps out into a lock: drawn" src="https://github.com/user-attachments/assets/3d112ac0-4f0e-47a6-a307-985523647c85" />
  <img width="1535" height="862" alt="With a white knight on e1, the game goes on" src="https://github.com/user-attachments/assets/efa38a66-7a7a-437a-901f-2959ee4b99f7" />

Chess960 is covered by `LockTest` only: a Chess960 game can't start from a FEN, and I couldn't reach
a lock in one by hand.

## The two halves, measured apart: `in-game` and `a-lock`

#907 proposed the first half alone: [`in-game`](in-game), three commits on scalachess 18.0.1
(`a498819`, 22 October 2026), the same commits as the `dead-position-lock` branch of
[cuneytinann/scalachess](https://github.com/cuneytinann/scalachess/tree/dead-position-lock). Its
hook is #916's `autoDraw` in Standard and From Position, and its `LockTest` (44 lines) holds the
in-play cases.

The second half was built alone as [`a-lock`](a-lock): the same `Lock.scala`, a 9-line hook in
`Position.opponentHasInsufficientMaterial` (the lock detector at the position itself, not in a
position without legal moves, and in the positions of #821's lookahead; Standard and From Position
only) and a `LockTest` of 37 lines. It draws 254 timeouts more than `in-game` (190,719): of the
52,147 it adds to today's, 51,909 are locks at the position itself, 16 of them locks of one side
only, and 238 come through the lookahead (237 kings stepping out of a pawn's check into a lock, one
lock after a forced capture of a rook). But it acts only when a flag falls: a dead game is still
played on, and a resignation in it is still a loss. #916 combines the two, moves the lookahead's
check into `playerHasInsufficientMaterial`, and adds the resignation on the opponent's turn and
Chess960.

Cost of `a-lock` (JVM 21, ns per call, after warm-up, over the 50,970 locks and 374,739 positions of
real games): ~150–190 on a locked position (the whole fixed point), ~30–35 on positions of real
games (a knight, rook or queen on the board: it stops at once). It runs only when a flag falls.

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
| #916: `Lock.scala` | byte-identical to `in-game/Lock.scala` and `a-lock/Lock.scala`, so the rows below hold for it |
| #916: the patches | `proposed/patches` applied on `176d2f7` give the `dead-position` branch's tree exactly |
| #916: tests | the branch's CI, all green: `LockTest`'s 17 tests and the rest of scalachess's |
| in-game, a: `Lock.scala` | the same answers as `mateless.js` on 3,643,939 positions (chasolver's two files, the timeouts, real games), both sides |
| in-game: `autoDraw` | today's, or (clock 0 or 1 and the lock for both sides), on the same positions; `AutodrawTest`'s and `LockTest`'s expectations hold (24 cases) |
| in-game: coverage | 190,465 of the 195,287 timeouts, 52,034 of them ended before the flag fell (51,893 lost on time today) |
| a: coverage | 190,719 of the 195,287 timeouts with the hook |
| a, c: scalachess's tests | the 22 standard-chess expectations on `opponentHasInsufficientMaterial` (`AutodrawTest`, `VariantTest`) hold with the hook |
| c: against the Rust engine | byte-identical, node counts included: chasolver's 68,950 positions, 119,921 + 192,457 rows, 4,000 + 24,392 + 222,006 games (flags ply by ply), 4,598 takebacks |

scalachess compiles with each patch set under its own flags (`-Wunused:all -Werror`), and the files
pass scalafmt 3.11.5 with scalachess's `.scalafmt.conf`. For #907's patches, munit was not
available where they were built, so the assertions of `LockTest` and `MatelessTest` were first run
by `check/ingame/InGame.scala`, `check/lock/LockCases.scala` and
`check/ladder/MatelessTestCases.scala`. The in-game test classes themselves were then run against a
small munit stand-in, on scalachess built from source with the patches. That found one fault, fixed
in `in-game`'s third commit: `LockTest` read its FENs with `fenToGame`, which goes through
`Fen.read` and drops the halfmove clock, so its clock test asked the lock at clock 0 and failed. It
reads them with `Fen.readWithMoveNumber` now and passes 8 of 8. Of test-kit's 52 top-level test
files, the 34 the stand-in could compile ran 32 test classes, 414 tests, all passing; the 18 that
need scalacheck or snapshot4s, and the tests in subdirectories, were left to sbt. #916's tests ran
under sbt, in its CI and in the lila-docker container. With sbt, on `176d2f7`:
`git am proposed/patches/*.patch && sbt testKit/test`.

Note for tools reading FENs into scalachess: `Fen.read` drops the halfmove clock;
`Fen.readWithMoveNumber` keeps it (the in-play hook and a game's flags read it).

## How this was made

I used Claude (Anthropic), in the Claude app, throughout this work.

- **What Claude did:** ported Mateless's lock detector to Scala (for #907); wrote #916's hooks and
  `LockTest` and gave them to me as patches; checked the tests' expectations against Mateless and
  the formatting with scalafmt; looked up where lila uses these functions; prepared the steps for
  the local test; drafted the first descriptions of #907 and #916, and this README.
- **What I did:** started Mateless and decided what it covers; chose #916's scope (in play first,
  then once more on time and on resignation; Chess960; not FEN); applied, committed and pushed
  every change; ran `LockTest` on my machine; reproduced the bug on lichess.org; tested the change
  on a local lila, including the rook position I set up; edited the texts.

## Layout

| | |
|---|---|
| `proposed/` | #916: `Lock.scala` and the five commits (`patches/`): the detector, the hook in `autoDraw`, the hook in `opponentHasInsufficientMaterial`, `LockTest`, the hook in `playerHasInsufficientMaterial` |
| `in-game/` | #907: `Lock.scala` and the three commits (`patches/`): the detector, the hook in `autoDraw`, `LockTest` |
| `a-lock/` | `Lock.scala` and the three commits (`patches/`): the detector, the hook at the flag, `LockTest` (the flag half, measured alone) |
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
