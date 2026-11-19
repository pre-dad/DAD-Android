import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { crc32 } from "node:zlib";

function zipEntry(name, contents) {
  const filename = Buffer.from(name);
  const data = Buffer.from(contents);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4);
  local.writeUInt32LE(crc32(data), 14);
  local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(filename.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
  central.writeUInt32LE(crc32(data), 16);
  central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(filename.length, 28);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(1, 8); end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + filename.length, 12);
  end.writeUInt32LE(local.length + filename.length + data.length, 16);
  return Buffer.concat([local, filename, data, central, filename, end]);
}

function check(bytes) {
  const directory = mkdtempSync(join(tmpdir(), "dad-release-test-"));
  try {
    const apk = join(directory, "release.apk");
    writeFileSync(apk, bytes);
    return spawnSync("bash", [resolve("scripts/check-release-apk.sh"), apk], { encoding: "utf8" });
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

test("a release with readable DEX and no debug markers passes", () => {
  const result = check(zipEntry("classes.dex", "release code"));
  assert.equal(result.status, 0, result.stderr);
});

test("a debug marker in DEX fails the release gate", () => {
  const result = check(zipEntry("classes.dex", "stub_backend"));
  assert.equal(result.status, 1);
  assert.match(result.stderr, /stub_backend/);
});

test("a corrupt APK never passes as free of debug code", () => {
  const result = check(Buffer.from("invalid apk"));
  assert.notEqual(result.status, 0);
  assert.doesNotMatch(result.stdout, /free of debug/);
});

test("an APK without DEX never passes as free of debug code", () => {
  const result = check(zipEntry("AndroidManifest.xml", "manifest"));
  assert.notEqual(result.status, 0);
  assert.doesNotMatch(result.stdout, /free of debug/);
});
