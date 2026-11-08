// Fails when user-visible text is written as a string literal in app/src/main instead of a resource.
//
// Compose has no stock lint for this (HardcodedText only reads XML layouts), so the rule is enforced
// here: a literal is user-visible when it is an argument of a text-bearing call (Text, *Button,
// DadSymbol, createChooser, ...), a named text argument (contentDescription =, label =, ...), or a
// sentence (two or more words) outside a developer-facing call such as require() or Exception().
// User-visible text belongs in res/values/strings.xml and reaches a model through UiText.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const TEXT_CALL = /^(Text|\w*Button|DadSymbol|DadBackBar|DadStatTile|FieldError|ErrorLine|StatusLine|PasswordField|AlertDialog|createChooser|setContentTitle|setContentText|NotificationChannel|newPlainText|Toast|makeText)$/;
const TEXT_ARGUMENT = /(?:^|[\s,(])(contentDescription|label|placeholder|supportingText|text|title|headline|onClickLabel|stateDescription)\s*=\s*$/;
const DEVELOPER_CALL = /^(require|check|checkNotNull|requireNotNull|error|TODO|println|IllegalStateException|IllegalArgumentException|IllegalAccessException|UnsupportedOperationException|SecurityException|RuntimeException|Exception|AssertionError|Regex|Log|ZoneId|SimpleDateFormat|ofPattern|forName|getInstance|getSharedPreferences|toRegex|Uri|parse)$/;
const CONSTANT_DECLARATION = /const\s+val\s+\w+\s*(?::\s*\w+)?\s*=\s*$/;
const DEBUG_DUMP = /\w=\$/;
const IDENTIFIER_LIKE = /^[a-z0-9_\-./:#?=&%+@]+$|^[A-Z0-9_]+$|^[a-z]+[A-Za-z0-9]*$/;

function decodeKotlin(literal) {
  return literal.replace(/\\u([0-9a-fA-F]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16))).replace(/\\./g, " ");
}

function words(text) {
  return text.replace(/\$\{[^}]*\}|\$[A-Za-z_]\w*/g, " ").match(/[A-Za-z]{2,}/g) ?? [];
}

// Walks Kotlin source, skipping comments, and reports each string literal with the innermost call it sits in.
// A lambda that directly follows a call, as in require(cond) { "message" }, belongs to that call.
export function scan(source, file = "source.kt") {
  const findings = [];
  const calls = [];
  let line = 1;
  let i = 0;
  let lastIdentifier = "";
  let closedCall = "";
  let closedAt = -1;
  while (i < source.length) {
    const c = source[i];
    if (c === "\n") { line++; i++; continue; }
    if (source.startsWith("//", i)) { while (i < source.length && source[i] !== "\n") i++; continue; }
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      const stop = end < 0 ? source.length : end + 2;
      line += (source.slice(i, stop).match(/\n/g) ?? []).length;
      i = stop;
      continue;
    }
    if (c === "'") { i += source[i + 1] === "\\" ? 4 : 3; continue; }
    if (source.startsWith('"""', i)) {
      const end = source.indexOf('"""', i + 3);
      const stop = end < 0 ? source.length : end + 3;
      line += (source.slice(i, stop).match(/\n/g) ?? []).length;
      i = stop;
      continue;
    }
    if (c === '"') {
      const start = i;
      const startLine = line;
      i++;
      while (i < source.length && source[i] !== '"') {
        if (source[i] === "\\") { i += 2; continue; }
        if (source.startsWith("${", i)) {
          let depth = 1;
          i += 2;
          while (i < source.length && depth > 0) {
            if (source[i] === "{") depth++;
            else if (source[i] === "}") depth--;
            else if (source[i] === '"') { i++; while (i < source.length && source[i] !== '"') i += source[i] === "\\" ? 2 : 1; }
            i++;
          }
          continue;
        }
        i++;
      }
      i++;
      const literal = source.slice(start + 1, i - 1);
      const before = source.slice(Math.max(0, start - 60), start);
      const call = calls.length ? calls[calls.length - 1] : "";
      const text = decodeKotlin(literal);
      const letters = words(text);
      if (letters.length > 0 && !IDENTIFIER_LIKE.test(literal)) {
        const contextual = TEXT_CALL.test(call) || TEXT_ARGUMENT.test(before);
        const sentence = letters.length >= 2 && /\s/.test(text.trim());
        const exempt = DEVELOPER_CALL.test(call) || CONSTANT_DECLARATION.test(before) || DEBUG_DUMP.test(literal);
        if ((contextual || sentence) && !exempt) {
          findings.push({ file, line: startLine, literal });
        }
      }
      continue;
    }
    if (c === "(") { calls.push(lastIdentifier); lastIdentifier = ""; i++; continue; }
    if (c === ")") { closedCall = calls.pop() ?? ""; closedAt = i; lastIdentifier = ""; i++; continue; }
    if (c === "{") { calls.push(/^\s*$/.test(source.slice(closedAt + 1, i)) ? closedCall : ""); lastIdentifier = ""; i++; continue; }
    if (c === "}") { calls.pop(); lastIdentifier = ""; i++; continue; }
    if (/[A-Za-z_]/.test(c)) {
      let j = i;
      while (j < source.length && /\w/.test(source[j])) j++;
      lastIdentifier = source.slice(i, j);
      i = j;
      continue;
    }
    if (!/\s/.test(c)) lastIdentifier = "";
    i++;
  }
  return findings;
}

function kotlinFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? kotlinFiles(path) : path.endsWith(".kt") ? [path] : [];
  });
}

export function scanTree(root) {
  return kotlinFiles(join(root, "app/src/main")).flatMap((path) =>
    scan(readFileSync(path, "utf8"), relative(root, path)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL("..", import.meta.url));
  const findings = scanTree(root);
  for (const f of findings) console.error(`${f.file}:${f.line}: hardcoded user-visible text ${JSON.stringify(f.literal)}; move it to res/values/strings.xml`);
  if (findings.length > 0) process.exit(1);
  console.log("No hardcoded user-visible strings in app/src/main.");
}
