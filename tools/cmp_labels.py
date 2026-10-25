import sys
# compare line-indexed outputs; skip the indices the reference (Rust) reports as fen-error
ref, got = {}, {}
for path, d in ((sys.argv[1], ref), (sys.argv[2], got)):
    for l in open(path):
        f = l.rstrip('\n').split('\t'); d.setdefault(f[0], []).append('\t'.join(f[1:]))
err_ref = {k for k, v in ref.items() if v[0].startswith('fen-error')}
err_got = {k for k, v in got.items() if v[0].startswith('fen-error')}
same = diff = 0; shown = 0
for k, v in ref.items():
    if k in err_ref: continue
    if got.get(k) == v: same += 1
    else:
        diff += 1
        if shown < 8: print('DIFF', k, 'rust', v, 'scala', got.get(k)); shown += 1
print(f'compared {same + diff}: same {same}, different {diff}; fen errors: rust {len(err_ref)}, scala {len(err_got)}, scala-only {len(err_got - err_ref)}, rust-only {len(err_ref - err_got)}')
