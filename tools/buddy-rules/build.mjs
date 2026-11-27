import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const revision = '016f048b5c636035ac0b56b0c4b9d89a742fba4a';
const sourceRoot = path.join(root, 'data/research/buddy-pinned', revision);
const outputRoot = path.join(root, 'core-domain/src/main/resources/buddy');
const testRoot = path.join(root, 'core-domain/src/test/resources/buddy');
const paths = ['data/seed-game.json', 'characters.js', 'data/professions.js', 'data/class-rules.js', 'model.js', 'core/actions.js', 'core/seed.js', 'core/seed-engine.js'];
const lock = JSON.parse(await readFile(path.join(outputRoot, 'sources-v1.json'), 'utf8'));
assert.equal(lock.revision, revision);
const sources = {};
const texts = {};
for (const relative of paths) {
  const url = `https://raw.githubusercontent.com/kskdmcph58-lgtm/FE_Rigging_Buddy/${revision}/${relative}`;
  let bytes;
  if (process.argv.includes('--offline')) {
    bytes = await readFile(path.join(sourceRoot, relative));
  } else {
    const response = await fetch(url, { signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error(`${response.status}: ${url}`);
    bytes = Buffer.from(await response.arrayBuffer());
  }
  sources[relative] = { url, sha256: createHash('sha256').update(bytes).digest('hex') };
  assert.equal(sources[relative].sha256, lock.sources[relative].sha256, `Source changed: ${relative}`);
  texts[relative] = bytes.toString('utf8');
  const destination = path.join(sourceRoot, relative);
  if (!process.argv.includes('--offline')) {
    await mkdir(path.dirname(destination), { recursive: true });
    await writeFile(destination, bytes);
  }
}
assert.equal(sources['data/seed-game.json'].sha256, '3f18aaed1217726e64f22623653fa19f68d88e0537485310c974bfcf0d71d32d');
const game = JSON.parse(texts['data/seed-game.json']);
const engine = await import(`data:text/javascript;base64,${Buffer.from(texts['core/seed-engine.js']).toString('base64')}`);
const context = vm.createContext({ window: {}, fetch: async () => ({ ok: true, json: async () => game }) });
for (const relative of paths.filter(relative => relative.endsWith('.js') && relative !== 'core/seed-engine.js')) {
  const script = new vm.Script(texts[relative], { filename: relative, importModuleDynamically: async () => engine });
  script.runInContext(context);
}
await context.window.RiggingSeed.load();
const { CHARACTERS: characters, PROFESSIONS: professions, RIDES: rides, CLASS_RULES: classRules, RiggingSeed: seed, RiggingActions: actions } = context.window;
const people = characters.filter(character => seed.internals.personId(character)).map(character => ({
  id: character.id, name: character.name, personId: seed.internals.personId(character),
  part3Only: character.part3Only === true, classRule: classRules[character.id] ?? {},
}));
const jobIds = new Set(professions.names.map(name => seed.internals.jobOf(name)));
const pick = (value, keys) => Object.fromEntries(keys.map(key => [key, value[key]]));
const rules = {
  schemaVersion: 1, revision, stats: game.stats,
  people,
  persons: Object.fromEntries([...new Set(people.map(person => person.personId))].map(id => [id, pick(game.persons[id], ['growth', 'guarantee'])])),
  professions: professions.names.map(name => ({ name, jobId: seed.internals.jobOf(name), part3Only: professions.part3Only.has(name), kind: professions.mountOf[name] ?? null, tier: seed.classTier(name), base: professions.base.includes(name) })),
  jobs: Object.fromEntries([...jobIds].map(id => [id, pick(game.jobs[id], ['allocationType', 'defaultRide', 'rideMultiplier', 'baseGrowth', 'growthBonus', 'levelBonuses'])])),
  rides: Object.fromEntries(Object.entries(game.rides).map(([id, ride]) => [id, { ...pick(ride, ['type', 'growth']), ...rides.byId[id], part3Only: rides.part3Only.has(id) }])),
};
const cases = [];
function addCase(character, profession, level, part3, route, ride, originSeed) {
  const state = { route, part3 };
  const input = { characterId: character.id, profession, level, part3, route, ride, seed: originSeed };
  const event = seed.internals.eventOf(state, character, { profession, level, part3, ride });
  assert(event);
  const simulation = engine.simulateLevel(originSeed, event);
  const effectiveRide = actions.usableRide(state, ride, professions.mountOf[profession], character.id, part3);
  cases.push({ input, allowed: actions.classAllowed(state, character.id, profession, part3), effectiveRide: effectiveRide ?? null,
    growth: event.growth, guarantee: event.guarantee, gains: simulation.gains, nextSeed: simulation.next });
}
const origins = [0, 1, 123456, 2147483647, 2147483648, 4294967295];
for (const [index, person] of people.entries()) {
  const character = characters.find(character => character.id === person.id);
  for (const part3 of [false, true]) {
    addCase(character, '平民', 34, part3, 'other', null, origins[index % origins.length]);
    addCase(character, professions.names[index % professions.names.length], 45, part3, 'other', null, origins[(index + 1) % origins.length]);
  }
}
for (const profession of professions.names) {
  for (const level of [34, 35, 44, 45]) {
    addCase(characters.find(character => character.id === 'c044'), profession, level, true, 'other', { id: 'RID_ロシナン', level: 5 }, 4294967295);
  }
}
for (const characterId of ['c044', 'c054', 'c017']) {
  for (const ride of rides.list) {
    const profession = professions.names.find(name => professions.mountOf[name] === ride.kind);
    for (const part3 of [false, true]) {
      for (const route of ['other', 'kai']) {
        for (const level of [1, 5]) {
          addCase(characters.find(character => character.id === characterId), profession, 35, part3, route, { id: ride.id, level }, 2147483648);
        }
      }
    }
  }
}
const bytes = Buffer.from(JSON.stringify(rules) + '\n');
const forecasts = [];
for (const originSeed of origins) {
  for (const skippedIndices of [[], [0, 2, 3]]) {
    const character = characters.find(character => character.id === 'c044');
    const ride = { id: 'RID_ロシナン', level: 5 };
    const item = { id: character.id, initialLevel: 34, initialProfession: '战车兵', records: [],
      seed: { value: originSeed, rows: Object.fromEntries(skippedIndices.map(index => [index, { status: 'skipped' }])) } };
    const state = { part3: true, route: 'other', roster: [item], rideMemory: { [character.id]: { horse: ride } } };
    const rows = seed.queueRows(state, item, character, 0, 10).map(row => ({
      level: row.level, seed: row.seed, skipped: row.status === 'skipped', examMinimum: row.examMinimum,
      gains: game.stats.map(stat => row.plus2.includes(stat) ? 2 : row.stats.includes(stat) ? 1 : 0),
    }));
    forecasts.push({ seed: originSeed, skippedIndices, rows });
  }
}
const manifest = { schemaVersion: 1, revision, sha256: createHash('sha256').update(bytes).digest('hex'), sources,
  unresolvedCharacters: characters.filter(character => !seed.internals.personId(character)).map(character => character.id),
  counts: { people: people.length, professions: professions.names.length, assignableRides: rides.list.length, differentialCases: cases.length, forecastCases: forecasts.length } };
await mkdir(outputRoot, { recursive: true });
await mkdir(testRoot, { recursive: true });
const outputs = [
  [path.join(outputRoot, 'rules-v1.json'), bytes],
  [path.join(outputRoot, 'sources-v1.json'), Buffer.from(JSON.stringify(manifest, null, 2) + '\n')],
  [path.join(testRoot, 'baseline-v1.json'), Buffer.from(JSON.stringify({ revision, cases, forecasts }) + '\n')],
];
for (const [destination, content] of outputs) {
  if (process.argv.includes('--check')) assert.deepEqual(await readFile(destination), content, `Generated output differs: ${destination}`);
  else await writeFile(destination, content);
}
console.log(JSON.stringify(manifest, null, 2));
