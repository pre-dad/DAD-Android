import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repo = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const binary = process.env.GITLEAKS_BINARY ? resolve(process.env.GITLEAKS_BINARY) : null;
const manifestPath = "contracts/foundation/manifest.json";

// The contract job runs every scripts/*.test.mjs without a gitleaks binary; the secret-scan job supplies one.
test("secret exceptions allow only the reviewed hashes in the vendored manifest", { skip: !binary && "GITLEAKS_BINARY not set" }, async () => {
  const root = await mkdtemp(join(tmpdir(), "dad-secret-config-"));
  const report = join(root, "report.json");
  const manifest = await readFile(join(repo, manifestPath), "utf8");
  const scan = () => {
    const result = spawnSync(binary, ["dir", ".", "--config", join(repo, ".gitleaks.toml"),
      "--redact", "--no-banner", "--report-format", "json", "--report-path", report], { cwd: root, encoding: "utf8" });
    assert.ifError(result.error);
    assert.ok(result.status === 0 || result.status === 1, result.stderr);
    return result.status;
  };
  try {
    await mkdir(dirname(join(root, manifestPath)), { recursive: true });
    await writeFile(join(root, manifestPath), manifest);
    assert.equal(scan(), 0, "reviewed hashes must not be flagged");

    // A synthetic high-entropy marker exercises the generic key rule without using a credential.
    const marker = 'api_key = "' + ["7fQ2nZ8xL5", "vM9pW3kC6jH4sB0dY"].join("") + '"\n';
    await writeFile(join(root, manifestPath), manifest + "\n" + marker);
    assert.equal(scan(), 1, "an unrelated key in the manifest must fail");
    const findings = JSON.parse(await readFile(report, "utf8"));
    assert.equal(findings.length, 1);
    assert.equal(findings[0].RuleID, "generic-api-key");

    await rm(join(root, manifestPath));
    await writeFile(join(root, "unrelated.json"), manifest);
    assert.equal(scan(), 1, "the same hashes in an unrelated file are not excepted");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
