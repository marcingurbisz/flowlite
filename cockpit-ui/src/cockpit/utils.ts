import type { ActivityStatus, HistoryEntryDto, LongRunningActivityFilter } from './types';

const padDateTimePart = (value: number) => value.toString().padStart(2, '0');

const timeZoneFormatter = new Intl.DateTimeFormat(undefined, {
  timeZoneName: 'shortOffset',
});

const formatTimeZoneLabel = (date: Date) =>
  timeZoneFormatter.formatToParts(date).find((part) => part.type === 'timeZoneName')?.value ?? 'local';

const parseDurationToSecondsOrNull = (value: string | null) => {
  const normalized = value?.trim().toLowerCase().replace(/\s+/g, '') ?? '';
  if (!normalized) return null;

  const matches = Array.from(normalized.matchAll(/(\d+(?:\.\d+)?)(h|m|s)/g));
  if (matches.length === 0) return null;

  const consumed = matches.map((match) => match[0]).join('');
  if (consumed !== normalized) return null;

  const totalSeconds = matches.reduce((total, [, rawValue, unit]) => {
    const amount = Number.parseFloat(rawValue);
    if (!Number.isFinite(amount) || amount <= 0) return total;
    if (unit === 'h') return total + amount * 60 * 60;
    if (unit === 'm') return total + amount * 60;
    return total + amount;
  }, 0);

  return totalSeconds > 0 ? Math.round(totalSeconds) : null;
};

const isDefaultLongRunningActivity = (activityStatus: ActivityStatus | null) =>
  activityStatus === 'Running' || activityStatus === 'Pending';

export const parseDurationToSeconds = (value: string | null, fallback: number) =>
  parseDurationToSecondsOrNull(value) ?? fallback;

export const toTestIdFragment = (value: string | null) =>
  (value ?? 'none').replace(/[^a-zA-Z0-9-]+/g, '-');

export const formatDateTime = (date: Date) =>
  `${date.getFullYear()}-${padDateTimePart(date.getMonth() + 1)}-${padDateTimePart(date.getDate())} ${padDateTimePart(date.getHours())}:${padDateTimePart(date.getMinutes())}:${padDateTimePart(date.getSeconds())} ${formatTimeZoneLabel(date)}`;

export const formatElapsedDuration = (durationMs: number) => {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
};

export const matchesLongRunningActivityFilter = (
  activityStatus: ActivityStatus | null,
  filter: LongRunningActivityFilter,
) => {
  if (activityStatus === null) return false;
  if (filter === 'default') return isDefaultLongRunningActivity(activityStatus);
  if (filter === 'all') return true;
  return activityStatus === filter;
};

export const historyStageLabel = (event: HistoryEntryDto) => {
  if (event.type === 'StageChanged' || event.type === 'ManualStageChanged') {
    return event.toStage ?? event.stage ?? null;
  }

  if ((event.type === 'StatusChanged' || event.type === 'Cancelled') && (event.toStatus === 'Completed' || event.toStatus === 'Cancelled')) {
    return null;
  }

  return event.stage ?? event.toStage ?? null;
};

export const historyDetailsLabel = (event: HistoryEntryDto) => {
  switch (event.type) {
    case 'Started':
      return event.toStatus ? `status → ${event.toStatus}` : '—';
    case 'EventAppended':
      return event.event ? `event=${event.event}` : '—';
    case 'StatusChanged':
    case 'Retried':
    case 'Cancelled':
      return event.fromStatus || event.toStatus
        ? `status: ${event.fromStatus ?? '—'} → ${event.toStatus ?? '—'}`
        : '—';
    case 'StageChanged':
      return [
        event.fromStage || event.toStage ? `${event.fromStage ?? '—'} → ${event.toStage ?? '—'}` : null,
        event.event ? `event=${event.event}` : null,
      ].filter(Boolean).join(' • ') || '—';
    case 'ManualStageChanged':
      return [
        `manual ${event.fromStage ?? '—'} → ${event.toStage ?? '—'}`,
        event.fromStatus || event.toStatus ? `status: ${event.fromStatus ?? '—'} → ${event.toStatus ?? '—'}` : null,
      ].filter(Boolean).join(' • ');
    case 'Error':
      return [
        event.fromStatus || event.toStatus ? `status: ${event.fromStatus ?? '—'} → ${event.toStatus ?? '—'}` : null,
        event.errorMessage ? `error=${event.errorMessage}` : null,
      ].filter(Boolean).join(' • ') || '—';
  }
};