type ConfirmationActionKind = 'retry' | 'cancel' | 'change-stage';

interface ConfirmationActionState {
  kind: ConfirmationActionKind;
  instanceIds: string[];
  targetStage?: string;
}
import { useEffect, useMemo, useRef, useState } from 'react';
import { Play, AlertCircle, CheckCircle, Clock, RefreshCw, ChevronRight, X, Search, Database, Copy } from 'lucide-react';

type InstanceStatus = 'Pending' | 'Running' | 'Completed' | 'Error' | 'Cancelled';
type ActivityStatus = 'Running' | 'Pending' | 'WaitingForTimer' | 'WaitingForEvent';
type HistoryEventType = 'Started' | 'EventAppended' | 'StatusChanged' | 'StageChanged' | 'Retried' | 'ManualStageChanged' | 'Cancelled' | 'Error';
type ActiveView = 'flows' | 'errors' | 'long-running' | 'instances';
type StatusFilter = 'all' | InstanceStatus;
type LongRunningActivityFilter = 'default' | 'all' | ActivityStatus;

interface FlowDto {
  flowId: string;
  diagram: string;
  stages: string[];
  notCompletedCount: number;
  errorCount: number;
  activeCount: number;
  completedCount: number;
  longRunningCount: number;
  stageBreakdown: Array<{
    stage: string;
    totalCount: number;
    errorCount: number;
  }>;
}

interface InstanceDto {
  flowId: string;
  flowInstanceId: string;
  stage: string | null;
  status: InstanceStatus | null;
  activityStatus: ActivityStatus | null;
  lastUpdatedAt: string;
  lastErrorMessage: string | null;
}

interface ErrorGroupDto {
  flowId: string;
  stage: string | null;
  count: number;
  instanceIds: string[];
}

interface HistoryEntryDto {
  occurredAt: string;
  type: HistoryEventType;
  stage?: string | null;
  fromStage?: string | null;
  toStage?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  event?: string | null;
  errorType?: string | null;
  errorMessage?: string | null;
  errorStackTrace?: string | null;
}

interface UiInstance {
  id: string;
  flowId: string;
  stage: string;
  status: InstanceStatus;
  activityStatus: ActivityStatus | null;
  updatedAt: Date;
  createdAt: Date;
  errorMessage: string | null;
}

interface MermaidWindow extends Window {
  mermaid?: {
    initialize: (config: Record<string, unknown>) => void;
    render: (id: string, diagram: string) => Promise<{ svg: string }>;
  };
}

interface CockpitLocationState {
  activeView: ActiveView;
  searchTerm: string;
  statusFilter: StatusFilter;
  stageFilter: string;
  errorMessageFilter: string;
  showIncompleteOnly: boolean;
  errorFlowFilter: string;
  errorStageFilter: string;
  errorMessageFilterErrors: string;
  longRunningFlowFilter: string;
  longRunningActivityFilter: LongRunningActivityFilter;
  longRunningThreshold: string;
  selectedInstanceFlowId: string | null;
  selectedInstanceId: string | null;
}

const activeViews: ActiveView[] = ['flows', 'errors', 'long-running', 'instances'];
const statusFilters: StatusFilter[] = ['all', 'Pending', 'Running', 'Completed', 'Error', 'Cancelled'];
const activityStatuses: ActivityStatus[] = ['Running', 'Pending', 'WaitingForTimer', 'WaitingForEvent'];
const longRunningActivityFilters: LongRunningActivityFilter[] = ['default', 'all', ...activityStatuses];
const defaultLongRunningThreshold = '1h';
const defaultLongRunningThresholdSeconds = 60 * 60;

const defaultLocationState: CockpitLocationState = {
  activeView: 'flows',
  searchTerm: '',
  statusFilter: 'all',
  stageFilter: 'all',
  errorMessageFilter: '',
  showIncompleteOnly: false,
  errorFlowFilter: 'all',
  errorStageFilter: 'all',
  errorMessageFilterErrors: '',
  longRunningFlowFilter: 'all',
  longRunningActivityFilter: 'default',
  longRunningThreshold: defaultLongRunningThreshold,
  selectedInstanceFlowId: null,
  selectedInstanceId: null,
};

const isActiveView = (value: string | null): value is ActiveView =>
  value !== null && activeViews.includes(value as ActiveView);

const isStatusFilter = (value: string | null): value is StatusFilter =>
  value !== null && statusFilters.includes(value as StatusFilter);

const isLongRunningActivityFilter = (value: string | null): value is LongRunningActivityFilter =>
  value !== null && longRunningActivityFilters.includes(value as LongRunningActivityFilter);

const normalizeFilterValue = (value: string | null) => {
  const trimmed = value?.trim();
  return trimmed ? trimmed : 'all';
};

const normalizeLongRunningThreshold = (value: string | null) => {
  const trimmed = value?.trim();
  return trimmed ? trimmed : defaultLongRunningThreshold;
};

const parsePositiveNumber = (value: string | null, fallback: number) => {
  const parsed = value ? Number.parseFloat(value) : Number.NaN;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};

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

const parseDurationToSeconds = (value: string | null, fallback: number) =>
  parseDurationToSecondsOrNull(value) ?? fallback;

const toTestIdFragment = (value: string | null) =>
  (value ?? 'none').replace(/[^a-zA-Z0-9-]+/g, '-');

const padDateTimePart = (value: number) => value.toString().padStart(2, '0');

const formatDateTime = (date: Date) =>
  `${date.getUTCFullYear()}-${padDateTimePart(date.getUTCMonth() + 1)}-${padDateTimePart(date.getUTCDate())} ${padDateTimePart(date.getUTCHours())}:${padDateTimePart(date.getUTCMinutes())}:${padDateTimePart(date.getUTCSeconds())} UTC`;

const formatElapsedDuration = (durationMs: number) => {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
};

const isDefaultLongRunningActivity = (activityStatus: ActivityStatus | null) =>
  activityStatus !== null && activityStatus !== 'WaitingForEvent';

const matchesLongRunningActivityFilter = (
  activityStatus: ActivityStatus | null,
  filter: LongRunningActivityFilter,
) => {
  if (activityStatus === null) return false;
  if (filter === 'default') return isDefaultLongRunningActivity(activityStatus);
  if (filter === 'all') return true;
  return activityStatus === filter;
};

const historyStageLabel = (event: HistoryEntryDto) => {
  if (event.type === 'StageChanged' || event.type === 'ManualStageChanged') {
    return event.toStage ?? event.stage ?? null;
  }

  if ((event.type === 'StatusChanged' || event.type === 'Cancelled') && (event.toStatus === 'Completed' || event.toStatus === 'Cancelled')) {
    return null;
  }

  return event.stage ?? event.toStage ?? null;
};

const historyDetailsLabel = (event: HistoryEntryDto) => {
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

const readLocationState = (): CockpitLocationState => {
  const params = new URLSearchParams(window.location.search);
  const activeViewParam = params.get('tab');
  const statusFilterParam = params.get('status');
  const selectedInstanceFlowId = params.get('instanceFlowId')?.trim() || null;
  const selectedInstanceId = params.get('instanceId')?.trim() || null;

  return {
    activeView: isActiveView(activeViewParam) ? activeViewParam : defaultLocationState.activeView,
    searchTerm: params.get('q') ?? defaultLocationState.searchTerm,
    statusFilter: isStatusFilter(statusFilterParam) ? statusFilterParam : defaultLocationState.statusFilter,
    stageFilter: normalizeFilterValue(params.get('stage')),
    errorMessageFilter: params.get('error') ?? defaultLocationState.errorMessageFilter,
    showIncompleteOnly: ['1', 'true', 'yes'].includes((params.get('incomplete') ?? '').toLowerCase()),
    errorFlowFilter: normalizeFilterValue(params.get('errorFlow')),
    errorStageFilter: normalizeFilterValue(params.get('errorStage')),
    errorMessageFilterErrors: params.get('errorMessage') ?? defaultLocationState.errorMessageFilterErrors,
    longRunningFlowFilter: normalizeFilterValue(params.get('lrFlow')),
    longRunningActivityFilter: isLongRunningActivityFilter(params.get('lrActivity'))
      ? params.get('lrActivity') as LongRunningActivityFilter
      : defaultLocationState.longRunningActivityFilter,
    longRunningThreshold: normalizeLongRunningThreshold(params.get('lrThreshold')),
    selectedInstanceFlowId: selectedInstanceFlowId && selectedInstanceId ? selectedInstanceFlowId : null,
    selectedInstanceId: selectedInstanceFlowId && selectedInstanceId ? selectedInstanceId : null,
  };
};

const buildLocationSearch = (state: CockpitLocationState) => {
  const params = new URLSearchParams();
  params.set('tab', state.activeView);

  if (state.searchTerm) params.set('q', state.searchTerm);
  if (state.statusFilter !== 'all') params.set('status', state.statusFilter);
  if (state.stageFilter !== 'all') params.set('stage', state.stageFilter);
  if (state.errorMessageFilter) params.set('error', state.errorMessageFilter);
  if (state.showIncompleteOnly) params.set('incomplete', '1');
  if (state.errorFlowFilter !== 'all') params.set('errorFlow', state.errorFlowFilter);
  if (state.errorStageFilter !== 'all') params.set('errorStage', state.errorStageFilter);
  if (state.errorMessageFilterErrors) params.set('errorMessage', state.errorMessageFilterErrors);
  if (state.longRunningFlowFilter !== 'all') params.set('lrFlow', state.longRunningFlowFilter);
  if (state.longRunningActivityFilter !== 'default') params.set('lrActivity', state.longRunningActivityFilter);
  if (state.longRunningThreshold.trim() && state.longRunningThreshold.trim() !== defaultLongRunningThreshold) {
    params.set('lrThreshold', state.longRunningThreshold.trim());
  }
  if (state.selectedInstanceFlowId && state.selectedInstanceId) params.set('instanceFlowId', state.selectedInstanceFlowId);
  if (state.selectedInstanceFlowId && state.selectedInstanceId) params.set('instanceId', state.selectedInstanceId);

  const search = params.toString();
  return search ? `?${search}` : '';
};

const statusConfig: Record<InstanceStatus, { bg: string; text: string; label: string }> = {
  Pending: { bg: 'bg-amber-500/20', text: 'text-amber-400', label: 'Pending' },
  Running: { bg: 'bg-blue-500/20', text: 'text-blue-400', label: 'Running' },
  Completed: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', label: 'Completed' },
  Error: { bg: 'bg-red-500/20', text: 'text-red-400', label: 'Error' },
  Cancelled: { bg: 'bg-zinc-500/20', text: 'text-zinc-400', label: 'Cancelled' },
};

const activityStatusStyles: Record<ActivityStatus, { bg: string; text: string; label: string }> = {
  Running: { bg: 'bg-blue-500/20', text: 'text-blue-300', label: 'Running' },
  Pending: { bg: 'bg-amber-500/20', text: 'text-amber-300', label: 'Pending' },
  WaitingForTimer: { bg: 'bg-violet-500/20', text: 'text-violet-300', label: 'Waiting for timer' },
  WaitingForEvent: { bg: 'bg-zinc-700', text: 'text-zinc-300', label: 'Waiting for event' },
};

const StatusBadge = ({ status }: { status: InstanceStatus }) => {
  const style = statusConfig[status];

  return (
    <span className={`px-2 py-1 rounded text-xs font-mono ${style.bg} ${style.text} flex items-center gap-1`}>
      {status === 'Error' && <AlertCircle size={12} />}
      {status === 'Completed' && <CheckCircle size={12} />}
      {status === 'Cancelled' && <X size={12} />}
      {status === 'Pending' && <Clock size={12} />}
      {status === 'Running' && <Play size={12} />}
      {style.label}
    </span>
  );
};

const ActivityBadge = ({ activityStatus }: { activityStatus: ActivityStatus }) => {
  const style = activityStatusStyles[activityStatus];

  return <span className={`px-2 py-1 rounded text-xs font-mono ${style.bg} ${style.text}`}>{style.label}</span>;
};

const FlowLiteCockpit = () => {
  const initialLocationState = useMemo(() => readLocationState(), []);
  const [activeView, setActiveView] = useState<ActiveView>(initialLocationState.activeView);
  const [flows, setFlows] = useState<FlowDto[]>([]);
  const [instances, setInstances] = useState<UiInstance[]>([]);
  const [errorsByGroup, setErrorsByGroup] = useState<ErrorGroupDto[]>([]);
  const [selectedFlowForDiagram, setSelectedFlowForDiagram] = useState<FlowDto | null>(null);
  const [instanceHistory, setInstanceHistory] = useState<HistoryEntryDto[]>([]);

  const [searchTerm, setSearchTerm] = useState(initialLocationState.searchTerm);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(initialLocationState.statusFilter);
  const [stageFilter, setStageFilter] = useState(initialLocationState.stageFilter);
  const [errorMessageFilter, setErrorMessageFilter] = useState(initialLocationState.errorMessageFilter);
  const [showIncompleteOnly, setShowIncompleteOnly] = useState(initialLocationState.showIncompleteOnly);
  const [errorFlowFilter, setErrorFlowFilter] = useState(initialLocationState.errorFlowFilter);
  const [errorStageFilter, setErrorStageFilter] = useState(initialLocationState.errorStageFilter);
  const [errorMessageFilterErrors, setErrorMessageFilterErrors] = useState(initialLocationState.errorMessageFilterErrors);
  const [longRunningFlowFilter, setLongRunningFlowFilter] = useState(initialLocationState.longRunningFlowFilter);
  const [longRunningActivityFilter, setLongRunningActivityFilter] = useState<LongRunningActivityFilter>(initialLocationState.longRunningActivityFilter);
  const [selectedInstances, setSelectedInstances] = useState<Set<string>>(new Set());
  const [showDiagram, setShowDiagram] = useState(false);
  const [showStackTrace, setShowStackTrace] = useState(false);
  const [expandedHistoryErrors, setExpandedHistoryErrors] = useState<Set<number>>(new Set());
  const [mermaidLoaded, setMermaidLoaded] = useState(false);
  const [longRunningThreshold, setLongRunningThreshold] = useState(initialLocationState.longRunningThreshold);
  const [showChangeStageModal, setShowChangeStageModal] = useState(false);
  const [changeStageTargetInstances, setChangeStageTargetInstances] = useState<string[]>([]);
  const [newStage, setNewStage] = useState('');
    const [actionConfirmation, setActionConfirmation] = useState<ConfirmationActionState | null>(null);
  const [selectedInstanceFlowId, setSelectedInstanceFlowId] = useState<string | null>(initialLocationState.selectedInstanceFlowId);
  const [selectedInstanceId, setSelectedInstanceId] = useState<string | null>(initialLocationState.selectedInstanceId);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const applyingLocationStateRef = useRef(false);
  const initializedHistoryRef = useRef(false);
  const copyFeedbackTimeoutRef = useRef<number | null>(null);

  const selectedInstance = useMemo(
    () => instances.find((instance) => instance.flowId === selectedInstanceFlowId && instance.id === selectedInstanceId) ?? null,
    [instances, selectedInstanceFlowId, selectedInstanceId],
  );
  const longRunningThresholdSeconds = useMemo(
    () => parseDurationToSeconds(longRunningThreshold, defaultLongRunningThresholdSeconds),
    [longRunningThreshold],
  );

  async function apiGet<T>(path: string): Promise<T> {
    const res = await fetch(path, { headers: { Accept: 'application/json' } });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`${res.status} ${res.statusText}: ${text}`);
    }
    return (await res.json()) as T;
  }

  async function apiPost(path: string): Promise<void> {
    const res = await fetch(path, { method: 'POST' });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`${res.status} ${res.statusText}: ${text}`);
    }
  }

  const refreshData = async (view: ActiveView = activeView) => {
    const flowPath = `/api/flows?longRunningThresholdSeconds=${encodeURIComponent(longRunningThresholdSeconds.toString())}`;
    const needsInstances = view !== 'flows' || (!!selectedInstanceFlowId && !!selectedInstanceId);
    const needsErrors = view === 'errors';

    const [flowRows, allRows, errorRows] = await Promise.all([
      apiGet<FlowDto[]>(flowPath),
      needsInstances ? apiGet<InstanceDto[]>('/api/instances') : Promise.resolve<InstanceDto[] | null>(null),
      needsErrors ? apiGet<ErrorGroupDto[]>('/api/errors') : Promise.resolve<ErrorGroupDto[] | null>(null),
    ]);

    setFlows(flowRows);
    if (errorRows !== null) {
      setErrorsByGroup(errorRows);
    }
    if (allRows !== null) {
      setInstances(
        allRows
          .filter((it) => it.status !== null)
          .map((it) => ({
            id: it.flowInstanceId,
            flowId: it.flowId,
            stage: it.stage ?? '',
            status: it.status as InstanceStatus,
            activityStatus: it.activityStatus,
            updatedAt: new Date(it.lastUpdatedAt),
            createdAt: new Date(it.lastUpdatedAt),
            errorMessage: it.lastErrorMessage,
          })),
      );
    }
  };

  const openSelectedInstance = (instance: UiInstance) => {
    setSelectedInstanceFlowId(instance.flowId);
    setSelectedInstanceId(instance.id);
  };

  const closeSelectedInstance = () => {
    setSelectedInstanceFlowId(null);
    setSelectedInstanceId(null);
  };

  const closeChangeStageModal = () => {
    setShowChangeStageModal(false);
    setChangeStageTargetInstances([]);
    setNewStage('');
  };

  const closeActionConfirmation = () => {
    setActionConfirmation(null);
  };

  const fallbackCopyText = (text: string) => {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', 'true');
    textarea.style.position = 'absolute';
    textarea.style.left = '-9999px';
    document.body.appendChild(textarea);
    textarea.select();
    const copied = document.execCommand('copy');
    document.body.removeChild(textarea);
    if (!copied) throw new Error('Copy command rejected');
  };

  const copyText = async (text: string, feedbackKey: string) => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        fallbackCopyText(text);
      }
    } catch (error) {
      console.error('Failed to copy text', error);
    }

    setCopiedKey(feedbackKey);
    if (copyFeedbackTimeoutRef.current !== null) {
      window.clearTimeout(copyFeedbackTimeoutRef.current);
    }
    copyFeedbackTimeoutRef.current = window.setTimeout(() => {
      setCopiedKey((current) => (current === feedbackKey ? null : current));
    }, 1500);
  };

  const renderCopyButton = (value: string, feedbackKey: string, testId: string) => (
    <button
      data-testid={testId}
      onClick={(event) => {
        event.stopPropagation();
        void copyText(value, feedbackKey);
      }}
      className="inline-flex items-center gap-1 rounded bg-zinc-800 px-2 py-1 text-[11px] text-zinc-300 transition-colors hover:bg-zinc-700"
    >
      <Copy size={12} />
      {copiedKey === feedbackKey ? 'Copied' : 'Copy'}
    </button>
  );

  const flowRefreshToken = activeView === 'flows' ? longRunningThresholdSeconds : null;

  useEffect(() => {
    void refreshData(activeView);
  }, [activeView, flowRefreshToken]);

  useEffect(() => {
    return () => {
      if (copyFeedbackTimeoutRef.current !== null) {
        window.clearTimeout(copyFeedbackTimeoutRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const applyStateFromLocation = () => {
      const next = readLocationState();
      applyingLocationStateRef.current = true;
      setActiveView(next.activeView);
      setSearchTerm(next.searchTerm);
      setStatusFilter(next.statusFilter);
      setStageFilter(next.stageFilter);
      setErrorMessageFilter(next.errorMessageFilter);
      setShowIncompleteOnly(next.showIncompleteOnly);
      setErrorFlowFilter(next.errorFlowFilter);
      setErrorStageFilter(next.errorStageFilter);
      setErrorMessageFilterErrors(next.errorMessageFilterErrors);
      setLongRunningFlowFilter(next.longRunningFlowFilter);
      setLongRunningActivityFilter(next.longRunningActivityFilter);
      setLongRunningThreshold(next.longRunningThreshold);
      setSelectedInstances(new Set());
      setSelectedInstanceFlowId(next.selectedInstanceFlowId);
      setSelectedInstanceId(next.selectedInstanceId);
      setSelectedFlowForDiagram(null);
      closeChangeStageModal();
    };

    window.addEventListener('popstate', applyStateFromLocation);
    return () => {
      window.removeEventListener('popstate', applyStateFromLocation);
    };
  }, []);

  useEffect(() => {
    if (applyingLocationStateRef.current) {
      applyingLocationStateRef.current = false;
      initializedHistoryRef.current = true;
      return;
    }

    const nextSearch = buildLocationSearch({
      activeView,
      searchTerm,
      statusFilter,
      stageFilter,
      errorMessageFilter,
      showIncompleteOnly,
      errorFlowFilter,
      errorStageFilter,
      errorMessageFilterErrors,
      longRunningFlowFilter,
      longRunningActivityFilter,
      longRunningThreshold,
      selectedInstanceFlowId,
      selectedInstanceId,
    });
    const nextUrl = `${window.location.pathname}${nextSearch}${window.location.hash}`;
    const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;

    if (currentUrl === nextUrl) {
      initializedHistoryRef.current = true;
      return;
    }

    if (!initializedHistoryRef.current) {
      window.history.replaceState(null, '', nextUrl);
      initializedHistoryRef.current = true;
      return;
    }

    window.history.pushState(null, '', nextUrl);
  }, [
    activeView,
    searchTerm,
    statusFilter,
    stageFilter,
    errorMessageFilter,
    showIncompleteOnly,
    errorFlowFilter,
    errorStageFilter,
    errorMessageFilterErrors,
    longRunningFlowFilter,
    longRunningActivityFilter,
    longRunningThreshold,
    selectedInstanceFlowId,
    selectedInstanceId,
  ]);

  useEffect(() => {
    if (!selectedInstanceFlowId || !selectedInstanceId) {
      setInstanceHistory([]);
      return;
    }

    void apiGet<HistoryEntryDto[]>(`/api/instances/${encodeURIComponent(selectedInstanceFlowId)}/${encodeURIComponent(selectedInstanceId)}/timeline`)
      .then(setInstanceHistory)
      .catch((e) => {
        console.error(e);
        setInstanceHistory([]);
      });
  }, [selectedInstanceFlowId, selectedInstanceId, selectedInstance]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;

      if (actionConfirmation) {
        closeActionConfirmation();
        return;
      }

      if (showChangeStageModal) {
        closeChangeStageModal();
        return;
      }

      if (selectedFlowForDiagram) {
        setSelectedFlowForDiagram(null);
        return;
      }

      if (selectedInstanceFlowId && selectedInstanceId) {
        closeSelectedInstance();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [actionConfirmation, showChangeStageModal, selectedFlowForDiagram, selectedInstanceFlowId, selectedInstanceId]);

  useEffect(() => {
    if ((window as MermaidWindow).mermaid) {
      setMermaidLoaded(true);
      return;
    }

    const script = document.createElement('script');
    script.src = 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js';
    script.async = true;
    script.onload = () => setMermaidLoaded(true);
    script.onerror = () => console.error('Failed to load Mermaid.js');
    document.body.appendChild(script);

    return () => {
      if (script.parentNode) script.parentNode.removeChild(script);
    };
  }, []);

  useEffect(() => {
    if (!mermaidLoaded) return;
    const maybeMermaid = (window as MermaidWindow).mermaid;
    maybeMermaid?.initialize({
      startOnLoad: false,
      theme: 'dark',
    });
  }, [mermaidLoaded]);

  useEffect(() => {
    setShowDiagram(false);
    setShowStackTrace(false);
    setExpandedHistoryErrors(new Set());
  }, [selectedInstanceFlowId, selectedInstanceId]);

  useEffect(() => {
    document.body.style.overflow = selectedInstanceFlowId || selectedInstanceId || selectedFlowForDiagram || showChangeStageModal || actionConfirmation ? 'hidden' : 'unset';
    return () => {
      document.body.style.overflow = 'unset';
    };
  }, [actionConfirmation, selectedInstanceFlowId, selectedInstanceId, selectedFlowForDiagram, showChangeStageModal]);

  useEffect(() => {
    setSelectedInstances(new Set());
  }, [activeView]);

  const MermaidDiagram = ({ diagram, id }: { diagram: string; id: string }) => {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
      if (!mermaidLoaded || !containerRef.current || !diagram) return;

      const renderDiagram = async () => {
        const maybeMermaid = (window as MermaidWindow).mermaid;
        if (!maybeMermaid) return;

        try {
          containerRef.current!.innerHTML = '';
          const diagramId = `mermaid-${id}-${Date.now()}`;
          const { svg } = await maybeMermaid.render(diagramId, diagram);
          containerRef.current!.innerHTML = svg;
          setError(false);
        } catch (err) {
          console.error('Mermaid rendering error:', err);
          setError(true);
          containerRef.current!.innerHTML = `<pre class="text-xs text-zinc-400 whitespace-pre-wrap">${diagram}</pre>`;
        }
      };

      void renderDiagram();
    }, [diagram, id]);

    if (!mermaidLoaded) return <div className="text-xs text-zinc-400 text-center py-4">Loading diagram renderer...</div>;
    if (error) return <pre className="text-xs text-zinc-400 whitespace-pre-wrap">{diagram}</pre>;

    return <div ref={containerRef} className="mermaid-diagram" />;
  };

  const stats = useMemo(
    () => ({
      totalFlows: flows.length,
      totalInstances: instances.length,
      errorInstances: instances.filter((i) => i.status === 'Error').length,
    }),
    [flows, instances],
  );

  const filteredInstances = useMemo(
    () =>
      instances.filter((instance) => {
        const matchesSearch =
          instance.id.toLowerCase().includes(searchTerm.toLowerCase()) ||
          instance.flowId.toLowerCase().includes(searchTerm.toLowerCase());
        const matchesStatus = statusFilter === 'all' || instance.status === statusFilter;
        const matchesStage = stageFilter === 'all' || instance.stage === stageFilter;
        const matchesErrorMessage =
          !errorMessageFilter ||
          (instance.errorMessage && instance.errorMessage.toLowerCase().includes(errorMessageFilter.toLowerCase()));
        const matchesIncomplete =
          !showIncompleteOnly || (instance.status !== 'Completed' && instance.status !== 'Cancelled');
        return matchesSearch && matchesStatus && matchesStage && matchesErrorMessage && matchesIncomplete;
      }),
    [instances, searchTerm, statusFilter, stageFilter, errorMessageFilter, showIncompleteOnly],
  );

  const filteredErrorGroups = useMemo(
    () =>
      errorsByGroup.filter((group) => {
        const matchesFlow = errorFlowFilter === 'all' || group.flowId === errorFlowFilter;
        const matchesStage = errorStageFilter === 'all' || (group.stage ?? '').toLowerCase().includes(errorStageFilter.toLowerCase());
        const matchesErrorText =
          !errorMessageFilterErrors ||
          instances.some(
            (i) =>
              i.flowId === group.flowId &&
              i.stage === (group.stage ?? '') &&
              i.errorMessage?.toLowerCase().includes(errorMessageFilterErrors.toLowerCase()),
          );

        return matchesFlow && matchesStage && matchesErrorText;
      }),
    [errorsByGroup, errorFlowFilter, errorStageFilter, errorMessageFilterErrors, instances],
  );

  const longRunningInstances = useMemo(() => {
    const thresholdMs = longRunningThresholdSeconds * 1000;
    const now = Date.now();

    return instances
      .filter((i) => matchesLongRunningActivityFilter(i.activityStatus, longRunningActivityFilter))
      .filter((i) => longRunningFlowFilter === 'all' || i.flowId === longRunningFlowFilter)
      .map((instance) => ({
        ...instance,
        inactiveDuration: now - instance.updatedAt.getTime(),
      }))
      .filter((instance) => instance.inactiveDuration > thresholdMs)
      .sort((a, b) => b.inactiveDuration - a.inactiveDuration);
  }, [instances, longRunningActivityFilter, longRunningFlowFilter, longRunningThresholdSeconds]);

  const toggleSelectInstance = (instanceId: string) => {
    const next = new Set(selectedInstances);
    if (next.has(instanceId)) next.delete(instanceId);
    else next.add(instanceId);
    setSelectedInstances(next);
  };

  const openInstancesView = ({
    search = '',
    status = 'all',
    stage = 'all',
    errorMessage = '',
    incompleteOnly = false,
  }: {
    search?: string;
    status?: StatusFilter;
    stage?: string;
    errorMessage?: string;
    incompleteOnly?: boolean;
  }) => {
    setActiveView('instances');
    setSearchTerm(search);
    setStatusFilter(status);
    setStageFilter(stage);
    setErrorMessageFilter(errorMessage);
    setShowIncompleteOnly(incompleteOnly);
    setSelectedInstances(new Set());
  };

  const openErrorsView = ({
    flow = 'all',
    stage = 'all',
    errorMessage = '',
  }: {
    flow?: string;
    stage?: string;
    errorMessage?: string;
  }) => {
    setActiveView('errors');
    setErrorFlowFilter(flow);
    setErrorStageFilter(stage);
    setErrorMessageFilterErrors(errorMessage);
    setSelectedInstances(new Set());
  };

  const clearErrorFilters = () => {
    openErrorsView({});
  };

  const selectAllErrorsInGroup = (instanceIds: string[]) => {
    const next = new Set(selectedInstances);
    instanceIds.forEach((instanceId) => next.add(instanceId));
    setSelectedInstances(next);
  };

  const deselectErrorsInGroup = (instanceIds: string[]) => {
    const next = new Set(selectedInstances);
    instanceIds.forEach((instanceId) => next.delete(instanceId));
    setSelectedInstances(next);
  };

  const clearInstanceFilters = () => {
    openInstancesView({});
  };

  const selectAllVisible = () => {
    const actionable = filteredInstances.filter((i) => i.status === 'Pending' || i.status === 'Error').map((i) => i.id);
    setSelectedInstances(new Set(actionable));
  };

  const deselectAll = () => setSelectedInstances(new Set());

  const executeRetry = async (instanceIds: string[]) => {
    const selected = instances.filter((i) => instanceIds.includes(i.id));
    await Promise.all(
      selected.map((i) => {
        const basePath = `/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}`;
        if (i.status === 'Error') return apiPost(`${basePath}/retry`);
        if (i.stage) return apiPost(`${basePath}/change-stage?stage=${encodeURIComponent(i.stage)}`);
        return Promise.resolve();
      }),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const executeCancel = async (instanceIds: string[]) => {
    const selected = instances.filter((i) => instanceIds.includes(i.id));
    await Promise.all(
      selected.map((i) => apiPost(`/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}/cancel`)),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const executeChangeStage = async (instanceIds: string[], stage: string) => {
    const selected = instances.filter((i) => instanceIds.includes(i.id));
    await Promise.all(
      selected.map((i) =>
        apiPost(
          `/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}/change-stage?stage=${encodeURIComponent(stage)}`,
        ),
      ),
    );

    closeChangeStageModal();
    setSelectedInstances(new Set());
    await refreshData();
  };

  const handleRetry = (instanceIds: string[]) => {
    setActionConfirmation({ kind: 'retry', instanceIds });
  };

  const handleCancel = (instanceIds: string[]) => {
    setActionConfirmation({ kind: 'cancel', instanceIds });
  };

  const handleChangeStage = (instanceIds: string[]) => {
    closeActionConfirmation();
    setChangeStageTargetInstances(instanceIds);
    setShowChangeStageModal(true);
  };

  const confirmChangeStage = () => {
    if (!newStage) return;
    setActionConfirmation({ kind: 'change-stage', instanceIds: changeStageTargetInstances, targetStage: newStage });
  };

  const confirmAction = async () => {
    if (!actionConfirmation) return;

    if (actionConfirmation.kind === 'retry') {
      await executeRetry(actionConfirmation.instanceIds);
    } else if (actionConfirmation.kind === 'cancel') {
      await executeCancel(actionConfirmation.instanceIds);
    } else if (actionConfirmation.targetStage) {
      await executeChangeStage(actionConfirmation.instanceIds, actionConfirmation.targetStage);
    }

    closeActionConfirmation();
  };

  const visibleHistory = instanceHistory;
  const latestErrorStackTrace =
    visibleHistory.find((h) => h.type === 'Error' && h.errorStackTrace)?.errorStackTrace ?? null;

  const flowForSelectedInstance = selectedInstance ? flows.find((f) => f.flowId === selectedInstance.flowId) : null;
  const selectedLongRunningIds = longRunningInstances.filter((instance) => selectedInstances.has(instance.id)).map((instance) => instance.id);
  const actionConfirmationInstances = actionConfirmation
    ? instances.filter((instance) => actionConfirmation.instanceIds.includes(instance.id))
    : [];
  const actionConfirmationTitle = actionConfirmation?.kind === 'retry'
    ? 'Are you sure you want to retry?'
    : actionConfirmation?.kind === 'cancel'
      ? 'Are you sure you want to cancel?'
      : 'Are you sure you want to change stage?';
  const actionConfirmationSummary = actionConfirmation?.kind === 'retry'
    ? 'Retry on non-error rows requeues the current stage by sending the instance back through the same stage entry point.'
    : actionConfirmation?.kind === 'cancel'
      ? 'Cancelling marks the selected instance(s) as cancelled and removes them from active processing.'
      : `Changing stage moves the selected instance(s) to ${actionConfirmation?.targetStage ?? 'the selected stage'} and reprocesses them from there on the next tick.`;

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100" style={{ fontFamily: '"IBM Plex Mono", monospace' }}>
      <header className="border-b border-zinc-800 bg-zinc-900/50 backdrop-blur">
        <div className="max-w-7xl mx-auto px-6 py-4">
          <h1 data-testid="cockpit-title" className="text-2xl font-bold tracking-tight text-zinc-50">FlowLite Cockpit</h1>
          <p className="text-sm text-zinc-500 mt-1">Workflow Engine Monitoring & Management</p>
          <p className="text-xs text-zinc-600 mt-1">
            flows: {stats.totalFlows} • instances: {stats.totalInstances} • errors: {stats.errorInstances}
          </p>
        </div>
      </header>

      <div className="max-w-7xl mx-auto px-6">
        <div className="border-b border-zinc-800">
          <div className="flex gap-6">
            {activeViews.map((view) => (
              <button
                key={view}
                data-testid={`tab-${view}`}
                onClick={() => setActiveView(view)}
                className={
                  'px-1 py-3 text-sm font-medium border-b-2 transition-colors ' +
                  (activeView === view
                    ? 'border-emerald-500 text-emerald-400'
                    : 'border-transparent text-zinc-500 hover:text-zinc-300')
                }
              >
                {view === 'long-running' ? 'Long Inactive' : view.charAt(0).toUpperCase() + view.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-6 py-6">
        {activeView === 'flows' && (
          <div className="space-y-4">
            <h2 data-testid="flows-heading" className="text-xl font-bold text-zinc-50 mb-4">Flow Definitions</h2>
            {flows.map((flow) => {
              return (
                <div key={flow.flowId} data-testid={`flow-card-${flow.flowId}`} className="bg-zinc-900 border border-zinc-800 rounded-lg p-6">
                  <div className="flex items-start justify-between mb-4">
                    <div>
                      <h3 className="text-lg font-bold text-zinc-50 font-mono">{flow.flowId}</h3>
                      <p className="text-xs text-zinc-500 mt-1">{flow.stages.length} stages</p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        data-testid={`flow-view-diagram-${flow.flowId}`}
                        onClick={() => setSelectedFlowForDiagram(flow)}
                        className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
                      >
                        View Diagram
                      </button>
                      {flow.longRunningCount > 0 && (
                        <button
                          data-testid={`flow-long-running-${flow.flowId}`}
                          onClick={() => {
                            setActiveView('long-running');
                            setLongRunningFlowFilter(flow.flowId);
                            setSelectedInstances(new Set());
                          }}
                          className="px-3 py-1 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded text-xs font-mono transition-colors"
                        >
                          {flow.longRunningCount} long inactive ⚠
                        </button>
                      )}
                      <button
                        data-testid={`flow-incomplete-${flow.flowId}`}
                        onClick={() => {
                          openInstancesView({
                            search: flow.flowId,
                            status: 'all',
                            stage: 'all',
                            errorMessage: '',
                            incompleteOnly: true,
                          });
                        }}
                        className="px-3 py-1 bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 rounded text-xs font-mono transition-colors"
                      >
                        {flow.notCompletedCount} incomplete →
                      </button>
                    </div>
                  </div>

                  {flow.stageBreakdown.length > 0 && (
                    <div className="mb-4">
                      <h4 className="text-xs font-bold text-zinc-500 uppercase tracking-wide mb-2">Active Stages</h4>
                      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2">
                        {flow.stageBreakdown.map((entry) => (
                          <button
                            key={entry.stage}
                            data-testid={`flow-stage-${flow.flowId}-${toTestIdFragment(entry.stage)}`}
                            onClick={() => {
                              openInstancesView({
                                search: flow.flowId,
                                status: 'all',
                                stage: entry.stage,
                              });
                            }}
                            className="bg-zinc-800/50 hover:bg-zinc-800 rounded p-3 text-left transition-colors group"
                          >
                            <div className="text-xs text-zinc-400 mb-1 truncate group-hover:text-zinc-300">{entry.stage}</div>
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-bold text-zinc-200">{entry.totalCount}</span>
                              {entry.errorCount > 0 && (
                                <button
                                  data-testid={`flow-stage-errors-${flow.flowId}-${toTestIdFragment(entry.stage)}`}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    openErrorsView({ flow: flow.flowId, stage: entry.stage });
                                  }}
                                  className="text-xs px-1.5 py-0.5 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded font-mono transition-colors"
                                >
                                  {entry.errorCount} err
                                </button>
                              )}
                            </div>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {activeView === 'errors' && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 mb-6">
              <select
                data-testid="errors-flow-filter"
                value={errorFlowFilter}
                onChange={(e) => setErrorFlowFilter(e.target.value)}
                className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              >
                <option value="all">All Flows</option>
                {flows.map((flow) => (
                  <option key={flow.flowId} value={flow.flowId}>
                    {flow.flowId}
                  </option>
                ))}
              </select>
              <input
                data-testid="errors-stage-filter"
                type="text"
                placeholder="Filter by stage..."
                value={errorStageFilter === 'all' ? '' : errorStageFilter}
                onChange={(e) => setErrorStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                data-testid="errors-message-filter"
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilterErrors}
                onChange={(e) => setErrorMessageFilterErrors(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <button
                data-testid="errors-clear-filters"
                onClick={clearErrorFilters}
                className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors"
              >
                Clear Filters
              </button>
            </div>

            {selectedInstances.size > 0 && (
              <div data-testid="errors-selection-bar" className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                <span className="text-sm text-emerald-400">{selectedInstances.size} error(s) selected</span>
                <div className="flex gap-2">
                  <button
                    data-testid="errors-retry-selected"
                    onClick={() => void handleRetry(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <RefreshCw size={14} /> Retry Selected ({selectedInstances.size})
                  </button>
                  <button
                    data-testid="errors-change-stage-selected"
                    onClick={() => handleChangeStage(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <ChevronRight size={14} /> Change Stage ({selectedInstances.size})
                  </button>
                  <button
                    data-testid="errors-cancel-selected"
                    onClick={() => void handleCancel(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <X size={14} /> Cancel Selected ({selectedInstances.size})
                  </button>
                  <button
                    data-testid="errors-deselect-selected"
                    onClick={deselectAll}
                    className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium"
                  >
                    Deselect
                  </button>
                </div>
              </div>
            )}

            {filteredErrorGroups.length === 0 ? (
              <div data-testid="errors-empty" className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                <p className="text-lg font-medium text-zinc-300 mb-2">No errors match filters</p>
              </div>
            ) : (
              filteredErrorGroups.map((group, idx) => {
                const groupInstances = instances.filter(
                  (i) => i.flowId === group.flowId && i.stage === (group.stage ?? '') && i.status === 'Error',
                );
                const groupTestIdSuffix = `${group.flowId}-${toTestIdFragment(group.stage)}`;

                return (
                  <div key={idx} data-testid={`error-group-${group.flowId}-${toTestIdFragment(group.stage)}`} className="bg-zinc-900 border border-red-900/30 rounded-lg p-6">
                    <div className="flex items-start justify-between mb-4">
                      <div>
                        <h3 className="text-lg font-bold text-zinc-50 font-mono">{group.flowId}</h3>
                        <p className="text-sm text-zinc-500 mt-1">Stage: <span className="font-mono">{group.stage}</span></p>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          data-testid={`error-group-select-all-${groupTestIdSuffix}`}
                          onClick={() => selectAllErrorsInGroup(groupInstances.map((instance) => instance.id))}
                          className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
                        >
                          Select All
                        </button>
                        <button
                          data-testid={`error-group-deselect-all-${groupTestIdSuffix}`}
                          onClick={() => deselectErrorsInGroup(groupInstances.map((instance) => instance.id))}
                          className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
                        >
                          Deselect All
                        </button>
                        <div className="px-3 py-1 bg-red-500/20 text-red-400 rounded text-xs font-mono">{group.count} errors</div>
                      </div>
                    </div>

                    <div className="space-y-2 mt-4">
                      {groupInstances.map((instance) => (
                        <div
                          key={instance.id}
                          data-testid={`error-instance-${instance.id}`}
                          className="bg-zinc-800/50 rounded p-3 hover:bg-zinc-800 transition-colors cursor-pointer"
                          onClick={() => openSelectedInstance(instance)}
                        >
                          <div className="flex items-start gap-3">
                            <input
                              data-testid={`error-instance-checkbox-${instance.id}`}
                              type="checkbox"
                              checked={selectedInstances.has(instance.id)}
                              onChange={() => toggleSelectInstance(instance.id)}
                              onClick={(e) => e.stopPropagation()}
                              className="w-4 h-4 rounded border-zinc-600 bg-zinc-700 mt-1 flex-shrink-0"
                            />
                            <div className="flex-1 min-w-0 space-y-1">
                              <div className="flex items-center gap-2">
                                <div className="text-sm font-mono text-zinc-300">{instance.id}</div>
                                {renderCopyButton(instance.id, `error-${instance.id}`, `copy-error-instance-id-${instance.id}`)}
                              </div>
                              <div className="text-xs text-red-400 mt-1">{instance.errorMessage}</div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        )}

        {activeView === 'long-running' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between mb-6">
              <div>
                <h2 data-testid="long-running-heading" className="text-xl font-bold text-zinc-50">Long Inactive Instances</h2>
                <p className="text-sm text-zinc-500 mt-1">Instances with no stage or status change beyond the threshold. Waiting for event is excluded by default.</p>
              </div>
              <div className="flex items-center gap-3">
                <select
                  data-testid="long-running-flow-filter"
                  value={longRunningFlowFilter}
                  onChange={(e) => setLongRunningFlowFilter(e.target.value)}
                  className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                >
                  <option value="all">All Flows</option>
                  {flows.map((flow) => (
                    <option key={flow.flowId} value={flow.flowId}>{flow.flowId}</option>
                  ))}
                </select>
                <select
                  data-testid="long-running-activity-filter"
                  value={longRunningActivityFilter}
                  onChange={(e) => setLongRunningActivityFilter(e.target.value as LongRunningActivityFilter)}
                  className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                >
                  <option value="default">Running + actionable pending</option>
                  <option value="all">All activity kinds</option>
                  <option value="Running">Running only</option>
                  <option value="Pending">Pending only</option>
                  <option value="WaitingForTimer">Waiting for timer</option>
                  <option value="WaitingForEvent">Waiting for event</option>
                </select>
                <label className="text-sm text-zinc-400">Threshold:</label>
                <input
                  data-testid="long-running-threshold"
                  type="text"
                  value={longRunningThreshold}
                  onChange={(e) => setLongRunningThreshold(e.target.value)}
                  className="w-28 bg-zinc-900 border border-zinc-800 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
                <span className="text-xs text-zinc-500">Examples: 30s, 1m, 1h 30m</span>
              </div>
            </div>

            {selectedLongRunningIds.length > 0 && (
              <div data-testid="long-running-selection-bar" className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                <span className="text-sm text-emerald-400">{selectedLongRunningIds.length} long inactive instance(s) selected</span>
                <div className="flex gap-2">
                  <button
                    data-testid="long-running-retry-selected"
                    onClick={() => void handleRetry(selectedLongRunningIds)}
                    className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <RefreshCw size={14} /> Retry Selected ({selectedLongRunningIds.length})
                  </button>
                  <button
                    data-testid="long-running-deselect-selected"
                    onClick={deselectAll}
                    className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium"
                  >
                    Deselect
                  </button>
                </div>
              </div>
            )}

            {longRunningInstances.length === 0 ? (
              <div data-testid="long-running-empty" className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                <p className="text-lg font-medium text-zinc-300 mb-2">No long inactive instances</p>
              </div>
            ) : (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-zinc-800/50">
                    <tr className="text-left text-zinc-400">
                      <th className="px-4 py-3 font-medium w-12"></th>
                      <th className="px-4 py-3 font-medium">Instance ID</th>
                      <th className="px-4 py-3 font-medium">Flow</th>
                      <th className="px-4 py-3 font-medium">Stage</th>
                      <th className="px-4 py-3 font-medium">Status</th>
                      <th className="px-4 py-3 font-medium">Activity</th>
                      <th className="px-4 py-3 font-medium">Inactive Duration</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-zinc-800">
                    {longRunningInstances.map((instance) => {
                      const durationText = formatElapsedDuration(instance.inactiveDuration);

                      return (
                        <tr
                          key={instance.id}
                          data-testid={`long-running-row-${instance.id}`}
                          className="hover:bg-zinc-800/30 transition-colors cursor-pointer"
                          onClick={() => openSelectedInstance(instance)}
                        >
                          <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                            <input
                              data-testid={`long-running-checkbox-${instance.id}`}
                              type="checkbox"
                              checked={selectedInstances.has(instance.id)}
                              onChange={() => toggleSelectInstance(instance.id)}
                              className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                            />
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">
                            <div className="flex items-center gap-2">
                              <span>{instance.id}</span>
                              {renderCopyButton(instance.id, `long-running-${instance.id}`, `copy-long-running-instance-id-${instance.id}`)}
                            </div>
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage}</td>
                          <td className="px-4 py-3"><div data-testid={`long-running-status-${instance.id}`}><StatusBadge status={instance.status} /></div></td>
                          <td className="px-4 py-3">
                            {instance.activityStatus && (
                              <div data-testid={`long-running-activity-${instance.id}`}>
                                <ActivityBadge activityStatus={instance.activityStatus} />
                              </div>
                            )}
                          </td>
                          <td className="px-4 py-3"><span className="px-2 py-1 rounded text-xs font-mono bg-amber-500/20 text-amber-400">{durationText}</span></td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {activeView === 'instances' && (
          <div className="space-y-4">
            <div className="flex items-center gap-4 mb-6">
              <div className="flex-1 relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-500" size={16} />
                <input
                  data-testid="instances-search"
                  type="text"
                  placeholder="Search by instance ID or flow ID..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
              </div>
              <select
                data-testid="instances-status-filter"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
                className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              >
                <option value="all">All Status</option>
                <option value="Pending">Pending</option>
                <option value="Running">Running</option>
                <option value="Error">Error</option>
                <option value="Completed">Completed</option>
                <option value="Cancelled">Cancelled</option>
              </select>
            </div>

            <div className="flex items-center gap-4 mb-4">
              <input
                data-testid="instances-stage-filter"
                type="text"
                placeholder="Filter by stage..."
                value={stageFilter === 'all' ? '' : stageFilter}
                onChange={(e) => setStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                data-testid="instances-error-filter"
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilter}
                onChange={(e) => setErrorMessageFilter(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <label className="inline-flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2 text-sm text-zinc-300">
                <input
                  data-testid="instances-incomplete-only"
                  type="checkbox"
                  checked={showIncompleteOnly}
                  onChange={(e) => setShowIncompleteOnly(e.target.checked)}
                  className="h-4 w-4 rounded border-zinc-600 bg-zinc-700"
                />
                <span>Incomplete Only</span>
              </label>
              <button data-testid="instances-select-all" onClick={selectAllVisible} className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors">Select All Actionable</button>
              <button data-testid="instances-deselect" onClick={deselectAll} className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors">Deselect</button>
              <button data-testid="instances-clear-filters" onClick={clearInstanceFilters} className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors">Clear Filters</button>
            </div>

            {selectedInstances.size > 0 && (
              <div data-testid="instances-selection-bar" className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                <span className="text-sm text-emerald-400">{selectedInstances.size} instance(s) selected</span>
                <div className="flex gap-2">
                  <button data-testid="instances-retry-selected" onClick={() => void handleRetry(Array.from(selectedInstances))} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
                  <button data-testid="instances-change-stage-selected" onClick={() => handleChangeStage(Array.from(selectedInstances))} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
                  <button data-testid="instances-cancel-selected" onClick={() => void handleCancel(Array.from(selectedInstances))} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
                </div>
              </div>
            )}

            <div className="bg-zinc-900 border border-zinc-800 rounded-lg overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-zinc-800/50">
                  <tr className="text-left text-zinc-400">
                    <th className="px-4 py-3 font-medium w-12"></th>
                    <th className="px-4 py-3 font-medium">Instance ID</th>
                    <th className="px-4 py-3 font-medium">Flow</th>
                    <th className="px-4 py-3 font-medium">Stage</th>
                    <th className="px-4 py-3 font-medium">Status</th>
                    <th className="px-4 py-3 font-medium">Updated</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-800">
                  {filteredInstances.map((instance) => (
                    <tr
                      key={instance.id}
                      data-testid="instances-row"
                      data-instance-id={instance.id}
                      className="hover:bg-zinc-800/30 transition-colors cursor-pointer"
                      onClick={() => openSelectedInstance(instance)}
                    >
                      <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                        {(instance.status === 'Pending' || instance.status === 'Error') && (
                          <input
                            data-testid={`instances-checkbox-${instance.id}`}
                            type="checkbox"
                            checked={selectedInstances.has(instance.id)}
                            onChange={() => toggleSelectInstance(instance.id)}
                            className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                          />
                        )}
                      </td>
                      <td className="px-4 py-3 font-mono text-xs text-zinc-300">
                        <div className="flex items-center gap-2">
                          <span>{instance.id}</span>
                          {renderCopyButton(instance.id, `instances-${instance.id}`, `copy-instance-list-id-${instance.id}`)}
                        </div>
                      </td>
                      <td data-testid="instance-flow-id" className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                      <td data-testid={`instance-stage-${instance.id}`} className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage || '—'}</td>
                      <td data-testid={`instance-status-${instance.id}`} className="px-4 py-3"><StatusBadge status={instance.status} /></td>
                      <td className="px-4 py-3 text-xs text-zinc-500">{formatDateTime(instance.updatedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {selectedInstance && (
        <div data-testid="instance-details-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={closeSelectedInstance}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-4xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div className="flex-1">
                <h3 data-testid="instance-details-title" className="text-lg font-bold text-zinc-50">Instance Details</h3>
                <div className="mt-1 flex items-center gap-2">
                  <p className="text-sm text-zinc-500 font-mono">{selectedInstance.id}</p>
                  {renderCopyButton(selectedInstance.id, `details-${selectedInstance.id}`, 'copy-instance-details-id')}
                </div>
              </div>
              <div className="flex items-center gap-2">
                {selectedInstance.status === 'Error' && (
                  <button data-testid="instance-retry" onClick={() => void handleRetry([selectedInstance.id])} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button data-testid="instance-change-stage" onClick={() => handleChangeStage([selectedInstance.id])} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button data-testid="instance-cancel" onClick={() => void handleCancel([selectedInstance.id])} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
                )}
                <button data-testid="instance-details-close" onClick={closeSelectedInstance} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
              </div>
            </div>

            <div className="p-6 space-y-6 overflow-y-auto flex-1 min-h-0">
              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Overview</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><div className="text-xs text-zinc-500 mb-1">Flow ID</div><div className="text-sm font-mono text-zinc-300">{selectedInstance.flowId}</div></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Current Stage</div><div data-testid="instance-details-stage" className="text-sm font-mono text-zinc-300">{selectedInstance.stage || '—'}</div></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Status</div><div data-testid="instance-details-status"><StatusBadge status={selectedInstance.status} /></div></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Updated At</div><div className="text-sm text-zinc-300">{formatDateTime(selectedInstance.updatedAt)}</div></div>
                </div>
              </div>

              <div>
                <button data-testid="instance-flow-diagram-toggle" onClick={() => setShowDiagram(!showDiagram)} className="flex items-center gap-2 text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3 hover:text-zinc-300 transition-colors">
                  <ChevronRight size={16} className={'transition-transform ' + (showDiagram ? 'rotate-90' : '')} /> Flow Diagram
                </button>
                {showDiagram && flowForSelectedInstance && (
                  <div className="bg-zinc-800/50 rounded-lg p-6 overflow-auto max-h-96">
                    <MermaidDiagram diagram={flowForSelectedInstance.diagram} id={`instance-${selectedInstance.id}`} />
                  </div>
                )}
              </div>

              {selectedInstance.status === 'Error' && (
                <div>
                  <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Error Information</h4>
                  <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 space-y-3">
                    <div><div className="text-xs text-zinc-500 mb-2">Error Message</div><div className="text-sm text-zinc-300">{selectedInstance.errorMessage}</div></div>
                    {latestErrorStackTrace && (
                      <div>
                        <button data-testid="instance-error-stacktrace-toggle" onClick={() => setShowStackTrace(!showStackTrace)} className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors mb-2">
                          <ChevronRight size={14} className={'transition-transform ' + (showStackTrace ? 'rotate-90' : '')} /> Stack Trace
                        </button>
                        {showStackTrace && <pre data-testid="instance-error-stacktrace" className="bg-zinc-900/50 rounded p-3 text-xs text-zinc-400 overflow-x-auto font-mono">{latestErrorStackTrace}</pre>}
                      </div>
                    )}
                  </div>
                </div>
              )}

              <div>
                <h4 data-testid="instance-event-history-title" className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Event History</h4>
                <div className="overflow-hidden rounded-lg border border-zinc-800">
                  <table className="w-full text-sm">
                    <thead className="bg-zinc-800/50 text-left text-zinc-400">
                      <tr>
                        <th className="px-4 py-3 font-medium">Timestamp</th>
                        <th className="px-4 py-3 font-medium">Type</th>
                        <th className="px-4 py-3 font-medium">Stage</th>
                        <th className="px-4 py-3 font-medium">Details</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-zinc-800 bg-zinc-900/30">
                  {visibleHistory.map((event, idx) => {
                    const isErrorEvent = event.type === 'Error';
                    const isExpanded = expandedHistoryErrors.has(idx);
                    const stageLabel = historyStageLabel(event);

                    return (
                      <tr key={idx} className="align-top">
                        <td data-testid={`instance-history-timestamp-${idx}`} className="px-4 py-3 text-xs text-zinc-500">{formatDateTime(new Date(event.occurredAt))}</td>
                        <td data-testid={`instance-history-type-${idx}`} className="px-4 py-3">
                          <span className={'text-sm font-mono ' + (isErrorEvent ? 'text-red-400' : 'text-zinc-300')}>{event.type}</span>
                        </td>
                        <td data-testid={`instance-history-stage-${idx}`} className="px-4 py-3">
                          <span className="text-sm font-mono text-zinc-200">{stageLabel ?? '—'}</span>
                        </td>
                        <td className="px-4 py-3">
                          <div data-testid={`instance-history-details-${idx}`} className="text-xs text-zinc-500">{historyDetailsLabel(event)}</div>
                          {isErrorEvent && event.errorStackTrace && (
                            <div className="mt-2">
                              <button
                                data-testid={`instance-history-stacktrace-toggle-${idx}`}
                                onClick={() => {
                                  const next = new Set(expandedHistoryErrors);
                                  if (next.has(idx)) next.delete(idx);
                                  else next.add(idx);
                                  setExpandedHistoryErrors(next);
                                }}
                                className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors"
                              >
                                <ChevronRight size={12} className={'transition-transform ' + (isExpanded ? 'rotate-90' : '')} /> Stack Trace
                              </button>
                              {isExpanded && <pre data-testid={`instance-history-stacktrace-${idx}`} className="bg-zinc-900/50 rounded p-2 text-xs text-zinc-400 overflow-x-auto font-mono mt-2">{event.errorStackTrace}</pre>}
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {selectedFlowForDiagram && (
        <div data-testid="flow-diagram-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={() => setSelectedFlowForDiagram(null)}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-5xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div>
                <h3 data-testid="flow-diagram-title" className="text-lg font-bold text-zinc-50 font-mono">{selectedFlowForDiagram.flowId}</h3>
                <p className="text-sm text-zinc-500 mt-1">Flow Diagram</p>
              </div>
              <button data-testid="flow-diagram-close" onClick={() => setSelectedFlowForDiagram(null)} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 overflow-y-auto flex-1 min-h-0"><div className="bg-zinc-800/50 rounded-lg p-8 overflow-auto"><MermaidDiagram diagram={selectedFlowForDiagram.diagram} id={`flow-${selectedFlowForDiagram.flowId}`} /></div></div>
          </div>
        </div>
      )}

      {showChangeStageModal && (
        <div data-testid="change-stage-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={closeChangeStageModal}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-zinc-50">Change Stage</h3>
                <p className="text-sm text-zinc-500 mt-1">{changeStageTargetInstances.length} instance(s) selected</p>
              </div>
              <button data-testid="change-stage-close" onClick={closeChangeStageModal} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="text-sm font-medium text-zinc-400 mb-2 block">Select New Stage</label>
                <select data-testid="change-stage-select" value={newStage} onChange={(e) => setNewStage(e.target.value)} className="w-full bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-3 text-sm focus:outline-none focus:border-blue-500">
                  <option value="">Select a stage...</option>
                  {flows.flatMap((flow) => flow.stages.map((stage) => (
                    <option key={`${flow.flowId}-${stage}`} value={stage}>{stage} ({flow.flowId})</option>
                  )))}
                </select>
              </div>
              <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3">
                <p className="text-xs text-amber-400">⚠️ Changing the stage will move the instance(s) to the selected stage. The engine will re-process from that stage on the next tick.</p>
              </div>
              <div className="flex gap-2 justify-end">
                <button data-testid="change-stage-cancel" onClick={closeChangeStageModal} className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium">Cancel</button>
                <button data-testid="change-stage-confirm" onClick={confirmChangeStage} disabled={!newStage} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-zinc-700 disabled:text-zinc-500 disabled:cursor-not-allowed rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Review Change</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {actionConfirmation && (
        <div data-testid="action-confirmation-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={closeActionConfirmation}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-lg" onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between">
              <div>
                <h3 data-testid="action-confirmation-title" className="text-lg font-bold text-zinc-50">{actionConfirmationTitle}</h3>
                <p className="text-sm text-zinc-500 mt-1">{actionConfirmation.instanceIds.length} instance(s) selected</p>
              </div>
              <button data-testid="action-confirmation-close" onClick={closeActionConfirmation} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 space-y-4">
              <div data-testid="action-confirmation-summary" className="bg-zinc-800/50 rounded-lg p-4 space-y-2">
                <p className="text-sm text-zinc-300">{actionConfirmationSummary}</p>
                {actionConfirmation.targetStage && (
                  <p className="text-xs text-zinc-400">Target stage: <span className="font-mono text-zinc-200">{actionConfirmation.targetStage}</span></p>
                )}
                <div className="text-xs text-zinc-500">
                  {actionConfirmationInstances.slice(0, 3).map((instance) => (
                    <div key={instance.id} className="font-mono">{instance.id} ({instance.flowId})</div>
                  ))}
                  {actionConfirmationInstances.length > 3 && (
                    <div>…and {actionConfirmationInstances.length - 3} more</div>
                  )}
                </div>
              </div>
              <div className="flex gap-2 justify-end">
                <button data-testid="action-confirmation-cancel" onClick={closeActionConfirmation} className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium">Cancel</button>
                <button data-testid="action-confirmation-confirm" onClick={() => void confirmAction()} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium">Yes, continue</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default FlowLiteCockpit;
