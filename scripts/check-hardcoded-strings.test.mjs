import assert from "node:assert/strict";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { scan, scanTree } from "./check-hardcoded-strings.mjs";

const literals = (source) => scan(source).map((f) => f.literal);

test("a Text literal with letters is reported with its line", () => {
  const found = scan('fun F() {\n    Text("Welcome back")\n}\n');
  assert.deepEqual(found, [{ file: "source.kt", line: 2, literal: "Welcome back" }]);
});

test("a single word is reported inside text-bearing calls and named arguments", () => {
  assert.deepEqual(literals('DadPrimaryButton("Verify", Modifier) {}'), ["Verify"]);
  assert.deepEqual(literals('Box(Modifier.semantics { contentDescription = "Warning" })'), ["Warning"]);
  assert.deepEqual(literals('OutlinedTextField(label = { Text("City") })'), ["City"]);
});

test("a sentence outside a developer-facing call is reported", () => {
  assert.deepEqual(literals('val notice = "Please sign in again."'), ["Please sign in again."]);
  assert.deepEqual(literals('Failed("Something went wrong. Please try again.")'), ["Something went wrong. Please try again."]);
});

test("resource lookups, tags and identifiers are not reported", () => {
  assert.deepEqual(literals('Text(stringResource(R.string.auth_verify), Modifier.testTag("verify-code"))'), []);
  assert.deepEqual(literals('val zone = ZoneId.of("America/New_York"); json.str("access_token")'), []);
  assert.deepEqual(literals('header("Authorization", "Bearer $access")'), []);
});

test("messages of require, check and thrown exceptions are developer-facing", () => {
  assert.deepEqual(literals('require(x) { "QR size out of range" }'), []);
  assert.deepEqual(literals('check(done) { "Session cleanup failed" }'), []);
  assert.deepEqual(literals('throw IllegalStateException("Something is not set up")'), []);
});

test("wire constants and debug dumps are exempt", () => {
  assert.deepEqual(literals('const val DETAIL = "The email or password is incorrect"'), []);
  assert.deepEqual(literals('override fun toString() = "Res(id=$id, args=$args)"'), []);
});

test("comments and string templates are skipped", () => {
  assert.deepEqual(literals('// Text("Hidden in a comment")\n/* Text("Nor here") */\nval a = 1'), []);
  assert.deepEqual(literals('Text("${a.b("x")} ok")').length, 1);
});

test("app/src/main has no hardcoded user-visible text", () => {
  const root = fileURLToPath(new URL("..", import.meta.url));
  assert.deepEqual(scanTree(root), []);
});
