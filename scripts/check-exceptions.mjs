#!/usr/bin/env node
// 工程例外台账检查（例外语义见 docs/exceptions.yaml）
// 检查 docs/exceptions.yaml：
//   a) 台账自身合法：必填键齐全、status 合法、dueDate 为 YYYY-MM-DD；
//   b) status=active 的条目若已过 dueDate 则 FAIL（过期例外必须处理，
//      不允许无到期日或到期未移除）——CI 门禁，任何过期即非零退出。
// 零依赖，hygiene 阶段直接 node 运行，与 check-field-catalog.mjs 同理。

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CATALOG_PATH = 'docs/exceptions.yaml';
const TODAY = new Date();
TODAY.setHours(0, 0, 0, 0);

const REQUIRED_KEYS = ['id', 'title', 'owner', 'dueDate', 'removalCondition', 'status'];
const VALID_STATUS = new Set(['active', 'removed']);
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

// ---------- 极简 YAML 子集解析（仅本台账使用的结构） ----------
// 支持：2 空格缩进、块映射、块序列（- id: ...）、plain/单引号标量、整行与行尾 # 注释。
function parseYaml(text) {
  const lines = [];
  for (const raw of text.split(/\r?\n/)) {
    if (raw.includes('\t')) {
      throw new Error('YAML 缩进不允许使用 Tab');
    }
    const content = raw.trim();
    if (!content || content.startsWith('#')) {
      continue;
    }
    lines.push({ indent: raw.length - raw.trimStart().length, content: raw.slice(raw.length - raw.trimStart().length).trimEnd() });
  }

  let pos = 0;

  function peek() {
    return lines[pos];
  }

  function parseBlock(indent) {
    const line = peek();
    if (!line || line.indent < indent) {
      return null;
    }
    return line.content === '-' || line.content.startsWith('- ') ? parseSeq(indent) : parseMap(indent);
  }

  function parseMap(indent) {
    const map = {};
    while (pos < lines.length) {
      const line = peek();
      if (line.indent < indent || line.content === '-' || line.content.startsWith('- ')) {
        break;
      }
      const match = /^([A-Za-z0-9_]+):(?:\s+(.*))?$/.exec(line.content);
      if (!match) {
        throw new Error(`无法解析的映射行：${line.content}`);
      }
      pos += 1;
      if (match[2] !== undefined && match[2] !== '') {
        map[match[1]] = parseScalar(match[2]);
      } else {
        const next = peek();
        map[match[1]] = next && next.indent > indent ? parseBlock(next.indent) : null;
      }
    }
    return map;
  }

  function parseSeq(indent) {
    const seq = [];
    while (pos < lines.length) {
      const line = peek();
      if (line.indent !== indent || !(line.content === '-' || line.content.startsWith('- '))) {
        break;
      }
      if (line.content === '-') {
        pos += 1;
        const next = peek();
        seq.push(next && next.indent > indent ? parseBlock(next.indent) : null);
        continue;
      }
      const rest = line.content.slice(2);
      if (/^[A-Za-z0-9_]+:(\s|$)/.test(rest)) {
        lines[pos] = { indent: indent + 2, content: rest };
        seq.push(parseMap(indent + 2));
      } else {
        pos += 1;
        seq.push(parseScalar(rest));
      }
    }
    return seq;
  }

  function parseScalar(raw) {
    const plain = raw.replace(/\s+#.*$/, '').trim();
    if (plain.startsWith("'")) {
      const end = plain.lastIndexOf("'");
      return plain.slice(1, end > 0 ? end : plain.length - 1);
    }
    if (/^-?\d+$/.test(plain)) {
      return Number.parseInt(plain, 10);
    }
    return plain;
  }

  return parseBlock(0);
}

// ---------- 台账校验 ----------

const errors = [];

function fail(message) {
  errors.push(message);
}

function parseDate(value, label) {
  if (typeof value !== 'string' || !DATE_RE.test(value)) {
    fail(`${label} dueDate 必须是 YYYY-MM-DD 字符串`);
    return null;
  }
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function main() {
  let catalog;
  try {
    catalog = parseYaml(readFileSync(join(ROOT, CATALOG_PATH), 'utf8'));
  } catch (error) {
    fail(`台账解析失败：${error.message}`);
    report();
    return;
  }
  if (catalog.version !== 1) {
    fail(`version 必须为 1，当前：${String(catalog.version)}`);
  }
  const exceptions = Array.isArray(catalog.exceptions) ? catalog.exceptions : [];
  if (exceptions.length === 0 && catalog.version === 1) {
    // 空台账合法（无例外即无过期），但需 version 校验过后才通过
  }

  const seenIds = new Set();
  for (const [index, entry] of exceptions.entries()) {
    const label = entry && typeof entry.id === 'string' ? entry.id : `exceptions[${index}]`;
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
      fail(`${label} 不是映射`);
      continue;
    }
    for (const key of REQUIRED_KEYS) {
      if (entry[key] === undefined || entry[key] === null || (typeof entry[key] === 'string' && entry[key].trim() === '')) {
        fail(`${label} 缺少必填键 ${key}`);
      }
    }
    if (seenIds.has(entry.id)) {
      fail(`${label} id 重复`);
    }
    seenIds.add(entry.id);
    if (!VALID_STATUS.has(entry.status)) {
      fail(`${label} status 必须是 active 或 removed，当前：${String(entry.status)}`);
      continue;
    }
    if (entry.status !== 'active') {
      continue; // removed 条目不再检查到期
    }
    if (typeof entry.owner !== 'string' || entry.owner.trim() === '') {
      fail(`${label} owner 必填（负责人）`);
    }
    const due = parseDate(entry.dueDate, label);
    if (due !== null && due.getTime() < TODAY.getTime()) {
      fail(`${label} 例外已过期（dueDate=${entry.dueDate} < 今天），请处理并标记 removed`);
    }
  }

  report();
}

function report() {
  for (const message of errors) {
    console.error(`FAIL ${message}`);
  }
  console.log(`例外条目 ${readCount()} 条，过期/非法 ${errors.length} 项`);
  if (errors.length > 0) {
    console.error('例外台账检查未通过');
    process.exitCode = 1;
  } else {
    console.log('例外台账检查通过');
  }
}

function readCount() {
  try {
    const text = readFileSync(join(ROOT, CATALOG_PATH), 'utf8');
    return text.split(/\r?\n/).filter((line) => /^\s{2}- id:/.test(line)).length;
  } catch {
    return 0;
  }
}

main();
