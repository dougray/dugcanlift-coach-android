import fs from 'fs';
const raw = JSON.parse(fs.readFileSync('exercises.json','utf8'));
const lib = raw.exercises.map(([name,muscle,equipment,category,level]) => ({
  name, muscle: raw.muscles[muscle]||'', equipment: raw.equipment[equipment]||'',
  category: raw.categories[category]||'', level: raw.levels[level]||'',
}));
const titleCase = (t) => (t||'').replace(/\b[a-z]/g, c => c.toUpperCase());
const search = (query, filter='') => {
  const needle = query.trim().toLowerCase();
  return lib.filter(x => !filter || x.equipment === filter)
            .filter(x => !needle || x.name.toLowerCase().includes(needle) || x.muscle.toLowerCase().includes(needle))
            .slice(0,40).map(x => x.name);
};
const out = {
  count: lib.length,
  bench: search('bench press'),
  hamstrings: search('hamstrings'),
  pressDumbbell: search('press','dumbbell'),
  empty: search(''),
  squat: search('squat'),
  equipTitleCase: Object.fromEntries([...new Set(lib.map(x=>x.equipment))].map(e=>[e,titleCase(e)])),
};
fs.writeFileSync('/tmp/browser-parity.json', JSON.stringify(out,null,1));
console.log('count',out.count,'bench',out.bench.length,'squat',out.squat.length);
