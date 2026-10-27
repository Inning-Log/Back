import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { load } from "cheerio";
import { parseKboScheduleResponse, parseKboScheduleMonthPage, parseKboScoreboardPage, mergeKboPages, allGamesTerminal } from "../src/fargate/kbo-pages.js";
import { stampPageObservation } from "../src/fargate/source.js";
import { normalizeStatus } from "../src/domain.js";
const fixture = name => readFile(new URL(`../fixtures/kbo/${name}`,import.meta.url),'utf8');

test("observed July 2024 ground-condition and rain rows are cancellations, never upcoming games", async () => {
  const parsed = parseKboScheduleResponse(JSON.parse(await fixture('weather-july-2024.json')),'2024-07','0,9,6');
  assert.equal(parsed.anomalies.length,0);
  const ground = parsed.games.filter(g=>g.statusText==='그라운드사정');
  assert.equal(ground.length,2);
  assert.ok(ground.every(g=>g.status==='CANCELLED' && g.score.home===null && g.score.away===null));
  assert.ok(parsed.games.filter(g=>g.statusText==='우천취소').every(g=>g.status==='CANCELLED'));
});

test("observed postseason revision shifts PO dates and removes old fixtures entirely", async () => {
  const old = parseKboScheduleMonthPage(await fixture('postseason-october-2026.html'),'2026-10');
  const latest = parseKboScheduleResponse(JSON.parse(await fixture('postseason-october-2026-updated.json')),'2026-10','3,4,5,7');
  assert.ok(old.games.some(g=>g.date==='2026-10-25'));
  assert.ok(!latest.games.some(g=>g.date==='2026-10-25'));
  assert.deepEqual(latest.games.filter(g=>g.gameType==='PLAYOFF').map(g=>[g.date,g.postseasonRound]),[
    ['2026-10-26',1],['2026-10-27',2],['2026-10-29',3],['2026-10-30',4]
  ]);
});

test("synthetic transient flags retain suspended score and inning, and never terminate the collector", async () => {
  const html=await fixture('scoreboard.html');
  for(const [flag,status] of [['우천지연','DELAYED'],['우천대기','DELAYED'],['6회말 우천중단','SUSPENDED'],['서스펜디드','SUSPENDED'],['순연','POSTPONED'],['우천취소','CANCELLED']]) {
    const $=load(html); $('.smsScore').slice(1).remove(); $('.flag').first().text(flag);
    const parsed=parseKboScoreboardPage($.html(),'2026-08-26').games;
    assert.equal(parsed[0].status,status);
    assert.equal(allGamesTerminal(parsed),['POSTPONED','CANCELLED'].includes(status));
    if(flag.startsWith('6회말')) {
      assert.equal(parsed[0].inning,6); assert.equal(parsed[0].half,'BOTTOM');
      assert.deepEqual(parsed[0].score,{away:0,home:5});
    }
  }
  assert.equal(normalizeStatus('우천'), 'UNKNOWN'); // Weather alone does not establish cancellation.
});

test("fresh cancellation beats a cached live scoreboard, while a newer resumed score wins", async () => {
  const scores=parseKboScoreboardPage(await fixture('scoreboard.html'),'2026-08-26');
  const old=stampPageObservation(scores,'scoreboard','2026-08-26T10:00:00Z').games[0];
  const cancellation={...old,status:'CANCELLED',inning:null,half:null,score:{away:null,home:null},
    meta:{...old.meta,resultObservedAt:'2026-08-26T10:05:00Z',resultSource:'SCHEDULE'}};
  assert.equal(mergeKboPages([cancellation],[old]).games[0].status,'CANCELLED');
  const resumed={...old,meta:{...old.meta,resultObservedAt:'2026-08-26T10:06:00Z'}};
  assert.equal(mergeKboPages([{...cancellation,status:'SUSPENDED'}],[resumed]).games[0].status,'LIVE');
});
