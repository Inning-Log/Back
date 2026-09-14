// Regenerate the producer/consumer contract fixtures without KBO or AWS requests.
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { parseKboScheduleMonthPage, parseKboScoreboardPage, mergeKboPages } from "../src/fargate/kbo-pages.js";
import { stampPageObservation } from "../src/fargate/source.js";
import { SnapshotPublisher, MemoryStateStore } from "../src/fargate/aws.js";

const output = new URL("../../../src/test/resources/game-snapshots/", import.meta.url);
await mkdir(output, {recursive:true});
const observedAt = "2026-09-13T15:03:00.000Z";
const scheduleAt = "2026-09-13T15:02:00.000Z";
const scheduleHtml = await readFile(new URL("../fixtures/kbo/schedule.html",import.meta.url),"utf8");
const scoreboardHtml = await readFile(new URL("../fixtures/kbo/scoreboard.html",import.meta.url),"utf8");
const schedule = stampPageObservation(parseKboScheduleMonthPage(scheduleHtml,"2026-08"),"schedule",scheduleAt);
const scoreboard = stampPageObservation(parseKboScoreboardPage(scoreboardHtml,"2026-08-26"),"scoreboard",observedAt);
const merged = mergeKboPages(schedule.games.filter(game => game.date === "2026-08-26"),scoreboard.games);
const sent = [];
const publisher = new SnapshotPublisher({aws:{snapshotQueueUrl:"fixture-only",region:"ap-northeast-2"}},new MemoryStateStore(),{
  now:() => Date.parse(observedAt),
  sqsClient:{send:async command => { sent.push(command.input.MessageBody); return {MessageId:"fixture"}; }},
});
await publisher.publishIfChanged({schemaVersion:1,source:"fixture-kbo-pages",mode:"schedule-month",date:"2026-08-01",month:"2026-08",
  observedAt:scheduleAt,games:schedule.games,anomalies:schedule.anomalies,requestMetrics:{logicalRequests:0,attempts:0}});
await publisher.publishIfChanged({schemaVersion:1,source:"fixture-kbo-pages",mode:"game-window",date:"2026-08-26",observedAt,
  games:merged.games,anomalies:merged.anomalies,requestMetrics:{logicalRequests:0,attempts:0}});
for (const [index,name] of ["crawler-month.json","crawler-game-window.json"].entries()) {
  await writeFile(new URL(name,output),JSON.stringify(JSON.parse(sent[index]),null,2)+"\n");
}
console.log("Generated two local-only backend contract fixtures.");
