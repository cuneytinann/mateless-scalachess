// lock_ref.js <fens>: the lock detector of mateless.js, White flagged and Black flagged, per FEN
const fs = require('fs'), E = require(process.env.MATELESS || '../../../Mateless/mateless.js'), L = E._internal;
const out = [];
for (const f of fs.readFileSync(process.argv[2], 'utf8').split('\n')) {
  if (!f) continue;
  const s = E.fromFen(f);
  out.push(s.error ? 'err' : (L.lockTest(s.position, E.WHITE) ? 1 : 0) + '\t' + (L.lockTest(s.position, E.BLACK) ? 1 : 0));
}
fs.writeFileSync(1, out.join('\n') + '\n');
