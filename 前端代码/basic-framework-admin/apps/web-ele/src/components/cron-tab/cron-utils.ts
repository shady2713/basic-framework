import type { CronItem, CronValue } from './types';

import { createDefaultCronValue } from './types';

type CronField = keyof CronValue;
type AnyCronItem = CronValue[CronField];

export function formatCronField(field: CronField, item: AnyCronItem): string {
  switch (item.type) {
    case '-1': {
      return '';
    }
    case '0': {
      return '*';
    }
    case '1': {
      return `${item.range.start}-${item.range.end}`;
    }
    case '2': {
      return field === 'week'
        ? `${item.loop.end}#${item.loop.start}`
        : `${item.loop.start}/${item.loop.end}`;
    }
    case '3': {
      const fallback = field === 'year' ? '' : '*';
      return item.appoint.length > 0 ? item.appoint.join(',') : fallback;
    }
    case '4': {
      return field === 'week' ? `${item.last}L` : 'L';
    }
    case '5': {
      return '?';
    }
    default: {
      return field === 'year' ? '' : '*';
    }
  }
}

export function formatCronExpression(value: CronValue): string {
  const fields = (
    ['second', 'minute', 'hour', 'day', 'month', 'week'] as const
  ).map((field) => formatCronField(field, value[field]));
  const year = formatCronField('year', value.year);
  return year ? `${fields.join(' ')} ${year}` : fields.join(' ');
}

export function parseCronExpression(expression: string): CronValue | undefined {
  const fields = expression.trim().split(/\s+/);
  if (fields.length < 6 || fields.length > 7) {
    return undefined;
  }
  const [second, minute, hour, day, month, week, year] = fields;
  if (!second || !minute || !hour || !day || !month || !week) {
    return undefined;
  }

  const value = createDefaultCronValue();
  const parsed =
    parseNumericField(second, value.second) &&
    parseNumericField(minute, value.minute) &&
    parseNumericField(hour, value.hour) &&
    parseDayField(day, value.day) &&
    parseNumericField(month, value.month) &&
    parseWeekField(week, value.week) &&
    parseYearField(year, value.year);
  if (!parsed) {
    return undefined;
  }
  return value;
}

function parseNumericField(segment: string, item: CronItem): boolean {
  if (segment === '*') {
    item.type = '0';
    return true;
  }
  const range = /^(\d+)-(\d+)$/.exec(segment);
  if (range) {
    item.type = '1';
    item.range.start = Number(range[1]);
    item.range.end = Number(range[2]);
    return true;
  }
  const loop = /^(\d+)\/(\d+)$/.exec(segment);
  if (loop) {
    item.type = '2';
    item.loop.start = Number(loop[1]);
    item.loop.end = Number(loop[2]);
    return true;
  }
  if (!/^\d+(?:,\d+)*$/.test(segment)) {
    return false;
  }
  item.type = '3';
  item.appoint = segment.split(',');
  return true;
}

function parseDayField(segment: string, item: CronItem): boolean {
  if (segment === '?') {
    item.type = '5';
    return true;
  } else if (segment === 'L') {
    item.type = '4';
    return true;
  }
  return parseNumericField(segment, item);
}

function parseWeekField(segment: string, item: CronValue['week']): boolean {
  if (segment === '?') {
    item.type = '5';
    return true;
  }
  const ordinal = /^(\d+)#(\d+)$/.exec(segment);
  if (ordinal) {
    item.type = '2';
    item.loop.start = Number(ordinal[2]);
    item.loop.end = ordinal[1];
    return true;
  }
  const last = /^(\d+)L$/.exec(segment);
  if (last) {
    const lastValue = last[1];
    if (!lastValue) {
      return false;
    }
    item.type = '4';
    item.last = lastValue;
    return true;
  }
  const range = /^(\d+)-(\d+)$/.exec(segment);
  if (range) {
    item.type = '1';
    item.range.start = range[1];
    item.range.end = range[2];
    return true;
  }
  if (segment === '*') {
    item.type = '0';
    return true;
  }
  if (!/^\d+(?:,\d+)*$/.test(segment)) {
    return false;
  }
  item.type = '3';
  item.appoint = segment.split(',');
  return true;
}

function parseYearField(segment: string | undefined, item: CronItem): boolean {
  if (segment === undefined) {
    item.type = '-1';
    return true;
  }
  return parseNumericField(segment, item);
}
