import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronRight, Copy, RefreshCw, X } from 'lucide-react';
import { StatusBadge } from './cockpit/badges';
import { MermaidDiagram } from './cockpit/MermaidDiagram';
import { buildLocationSearch, readLocationState } from './cockpit/locationState';
import {
  activeViews,
  defaultLongRunningThresholdSeconds,
  toUiInstance,
  type ActiveView,
  type ConfirmationActionState,
  type ErrorGroupDto,
  type FlowDto,
  type HistoryEntryDto,
  type InstanceDto,
  type LongRunningActivityFilter,
  type StatusFilter,
  type UiInstance,
} from './cockpit/types';
import { formatDateTime, historyDetailsLabel, historyStageLabel, parseDurationToSeconds } from './cockpit/utils';
import { ErrorsView } from './cockpit/views/ErrorsView';
import { FlowsView } from './cockpit/views/FlowsView';
import { InstancesView } from './cockpit/views/InstancesView';
import { LongRunningView } from './cockpit/views/LongRunningView';

interface MermaidHostWindow extends Window {
  mermaid?: {
    initialize: (config: Record<string, unknown>) => void;
  };
}

const FlowLiteCockpit = () => {
  const initialLocationState = useMemo(() => readLocationState(), []);
  const [activeView, setActiveView] = useState<ActiveView>(initialLocationState.activeView);
  const [flows, setFlows] = useState<FlowDto[]>([]);
  const [instances, setInstances] = useState<UiInstance[]>([]);
  const [errorsByGroup, setErrorsByGroup] = useState<ErrorGroupDto[]>([]);
  const [selectedFlowForDiagram, setSelectedFlowForDiagram] = useState<FlowDto | null>(null);
  const [selectedInstance, setSelectedInstance] = useState<UiInstance | null>(null);
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

  const longRunningThresholdSeconds = useMemo(
    () => parseDurationToSeconds(longRunningThreshold, defaultLongRunningThresholdSeconds),
    [longRunningThreshold],
  );

  async function apiGet<T>(path: string): Promise<T> {
    const response = await fetch(path, { headers: { Accept: 'application/json' } });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${response.status} ${response.statusText}: ${text}`);
    }
    return (await response.json()) as T;
  }

  async function apiPost(path: string): Promise<void> {
    const response = await fetch(path, { method: 'POST' });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`${response.status} ${response.statusText}: ${text}`);
    }
  }

  const hasInstanceFiltersApplied =
    searchTerm.trim() !== '' ||
    statusFilter !== 'all' ||
    stageFilter !== 'all' ||
    errorMessageFilter.trim() !== '' ||
    showIncompleteOnly;

  const refreshData = async (view: ActiveView = activeView) => {
    const flowPath = `/api/flows?longRunningThresholdSeconds=${encodeURIComponent(longRunningThresholdSeconds.toString())}`;
    const instancesParams = new URLSearchParams();
    const errorsParams = new URLSearchParams();
    let instancesPath: string | null = null;
    let errorsPath: string | null = null;

    if (view === 'errors') {
      if (errorFlowFilter !== 'all') {
        instancesParams.set('flowId', errorFlowFilter);
        errorsParams.set('flowId', errorFlowFilter);
      }
      if (errorStageFilter !== 'all' && errorStageFilter.trim() !== '') {
        instancesParams.set('stage', errorStageFilter.trim());
        errorsParams.set('stage', errorStageFilter.trim());
      }
      if (errorMessageFilterErrors.trim() !== '') {
        instancesParams.set('errorMessage', errorMessageFilterErrors.trim());
        errorsParams.set('errorMessage', errorMessageFilterErrors.trim());
      }
      instancesParams.set('bucket', 'error');
      errorsPath = `/api/errors?${errorsParams.toString()}`;
      instancesPath = `/api/instances?${instancesParams.toString()}`;
    }

    if (view === 'long-running') {
      if (longRunningFlowFilter !== 'all') {
        instancesParams.set('flowId', longRunningFlowFilter);
      }
      instancesParams.set('bucket', 'active');
      instancesParams.set('activityStatus', longRunningActivityFilter);
      instancesParams.set('longInactiveThresholdSeconds', longRunningThresholdSeconds.toString());
      instancesPath = `/api/instances?${instancesParams.toString()}`;
    }

    if (view === 'instances' && hasInstanceFiltersApplied) {
      if (searchTerm.trim() !== '') instancesParams.set('q', searchTerm.trim());
      if (statusFilter !== 'all') instancesParams.set('status', statusFilter);
      if (stageFilter !== 'all' && stageFilter.trim() !== '') instancesParams.set('stage', stageFilter.trim());
      if (errorMessageFilter.trim() !== '') instancesParams.set('errorMessage', errorMessageFilter.trim());
      if (showIncompleteOnly) instancesParams.set('incompleteOnly', 'true');
      instancesPath = `/api/instances?${instancesParams.toString()}`;
    }

    const [flowRows, allRows, errorRows] = await Promise.all([
      apiGet<FlowDto[]>(flowPath),
      instancesPath ? apiGet<InstanceDto[]>(instancesPath) : Promise.resolve<InstanceDto[] | null>(null),
      errorsPath ? apiGet<ErrorGroupDto[]>(errorsPath) : Promise.resolve<ErrorGroupDto[] | null>(null),
    ]);

    setFlows(flowRows);
    setErrorsByGroup(errorRows ?? []);
    setInstances((allRows ?? []).filter((item) => item.status !== null).map(toUiInstance));
  };

  const openSelectedInstance = (instance: UiInstance) => {
    setSelectedInstanceFlowId(instance.flowId);
    setSelectedInstanceId(instance.id);
    setSelectedInstance(instance);
  };

  const closeSelectedInstance = () => {
    setSelectedInstanceFlowId(null);
    setSelectedInstanceId(null);
    setSelectedInstance(null);
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
  const errorsRefreshToken = activeView === 'errors'
    ? `${errorFlowFilter}|${errorStageFilter}|${errorMessageFilterErrors}`
    : null;
  const longRunningRefreshToken = activeView === 'long-running'
    ? `${longRunningFlowFilter}|${longRunningActivityFilter}|${longRunningThresholdSeconds}`
    : null;
  const instancesRefreshToken = activeView === 'instances'
    ? `${searchTerm}|${statusFilter}|${stageFilter}|${errorMessageFilter}|${showIncompleteOnly}`
    : null;

  useEffect(() => {
    void refreshData(activeView);
  }, [activeView, errorsRefreshToken, flowRefreshToken, instancesRefreshToken, longRunningRefreshToken]);

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
      setSelectedInstance(null);
      setInstanceHistory([]);
      return;
    }

    void Promise.all([
      apiGet<InstanceDto>(`/api/instances/${encodeURIComponent(selectedInstanceFlowId)}/${encodeURIComponent(selectedInstanceId)}`),
      apiGet<HistoryEntryDto[]>(`/api/instances/${encodeURIComponent(selectedInstanceFlowId)}/${encodeURIComponent(selectedInstanceId)}/timeline`),
    ])
      .then(([instance, history]) => {
        setSelectedInstance(toUiInstance(instance));
        setInstanceHistory(history);
      })
      .catch((error) => {
        console.error(error);
        setSelectedInstance(null);
        setInstanceHistory([]);
      });
  }, [instances, selectedInstanceFlowId, selectedInstanceId]);

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
    if ((window as MermaidHostWindow).mermaid) {
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
    (window as MermaidHostWindow).mermaid?.initialize({
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

  const stats = useMemo(
    () => ({
      totalFlows: flows.length,
      totalInstances: flows.reduce((total, flow) => total + flow.activeCount + flow.errorCount + flow.completedCount, 0),
      errorInstances: flows.reduce((total, flow) => total + flow.errorCount, 0),
    }),
    [flows],
  );

  const filteredInstances = instances;
  const filteredErrorGroups = errorsByGroup;

  const longRunningInstances = useMemo(() => {
    const now = Date.now();

    return instances
      .map((instance) => ({
        ...instance,
        inactiveDuration: now - instance.updatedAt.getTime(),
      }))
      .sort((a, b) => b.inactiveDuration - a.inactiveDuration);
  }, [instances]);

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
    const actionable = filteredInstances.filter((instance) => instance.status === 'Pending' || instance.status === 'Error').map((instance) => instance.id);
    setSelectedInstances(new Set(actionable));
  };

  const deselectAll = () => setSelectedInstances(new Set());

  const executeRetry = async (instanceIds: string[]) => {
    const selected = instances.filter((instance) => instanceIds.includes(instance.id));
    await Promise.all(
      selected.map((instance) => {
        const basePath = `/api/instances/${encodeURIComponent(instance.flowId)}/${encodeURIComponent(instance.id)}`;
        if (instance.status === 'Error') return apiPost(`${basePath}/retry`);
        if (instance.stage) return apiPost(`${basePath}/change-stage?stage=${encodeURIComponent(instance.stage)}`);
        return Promise.resolve();
      }),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const executeCancel = async (instanceIds: string[]) => {
    const selected = instances.filter((instance) => instanceIds.includes(instance.id));
    await Promise.all(
      selected.map((instance) => apiPost(`/api/instances/${encodeURIComponent(instance.flowId)}/${encodeURIComponent(instance.id)}/cancel`)),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const executeChangeStage = async (instanceIds: string[], stage: string) => {
    const selected = instances.filter((instance) => instanceIds.includes(instance.id));
    await Promise.all(
      selected.map((instance) => apiPost(`/api/instances/${encodeURIComponent(instance.flowId)}/${encodeURIComponent(instance.id)}/change-stage?stage=${encodeURIComponent(stage)}`)),
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
  const latestErrorStackTrace = visibleHistory.find((entry) => entry.type === 'Error' && entry.errorStackTrace)?.errorStackTrace ?? null;
  const flowForSelectedInstance = selectedInstance ? flows.find((flow) => flow.flowId === selectedInstance.flowId) : null;
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
          <FlowsView
            flows={flows}
            onViewDiagram={setSelectedFlowForDiagram}
            onOpenLongRunning={(flowId) => {
              setActiveView('long-running');
              setLongRunningFlowFilter(flowId);
              setSelectedInstances(new Set());
            }}
            onOpenInstances={openInstancesView}
            onOpenErrors={openErrorsView}
          />
        )}

        {activeView === 'errors' && (
          <ErrorsView
            flows={flows}
            filteredErrorGroups={filteredErrorGroups}
            instances={instances}
            selectedInstances={selectedInstances}
            errorFlowFilter={errorFlowFilter}
            errorStageFilter={errorStageFilter}
            errorMessageFilterErrors={errorMessageFilterErrors}
            setErrorFlowFilter={setErrorFlowFilter}
            setErrorStageFilter={setErrorStageFilter}
            setErrorMessageFilterErrors={setErrorMessageFilterErrors}
            clearErrorFilters={clearErrorFilters}
            deselectAll={deselectAll}
            toggleSelectInstance={toggleSelectInstance}
            selectAllErrorsInGroup={selectAllErrorsInGroup}
            deselectErrorsInGroup={deselectErrorsInGroup}
            openSelectedInstance={openSelectedInstance}
            handleRetry={handleRetry}
            handleChangeStage={handleChangeStage}
            handleCancel={handleCancel}
            renderCopyButton={renderCopyButton}
          />
        )}

        {activeView === 'long-running' && (
          <LongRunningView
            flows={flows}
            longRunningFlowFilter={longRunningFlowFilter}
            longRunningActivityFilter={longRunningActivityFilter}
            longRunningThreshold={longRunningThreshold}
            longRunningInstances={longRunningInstances}
            selectedLongRunningIds={selectedLongRunningIds}
            selectedInstances={selectedInstances}
            setLongRunningFlowFilter={setLongRunningFlowFilter}
            setLongRunningActivityFilter={setLongRunningActivityFilter}
            setLongRunningThreshold={setLongRunningThreshold}
            deselectAll={deselectAll}
            toggleSelectInstance={toggleSelectInstance}
            openSelectedInstance={openSelectedInstance}
            handleRetry={handleRetry}
            renderCopyButton={renderCopyButton}
          />
        )}

        {activeView === 'instances' && (
          <InstancesView
            searchTerm={searchTerm}
            statusFilter={statusFilter}
            stageFilter={stageFilter}
            errorMessageFilter={errorMessageFilter}
            showIncompleteOnly={showIncompleteOnly}
            selectedInstances={selectedInstances}
            hasInstanceFiltersApplied={hasInstanceFiltersApplied}
            filteredInstances={filteredInstances}
            setSearchTerm={setSearchTerm}
            setStatusFilter={setStatusFilter}
            setStageFilter={setStageFilter}
            setErrorMessageFilter={setErrorMessageFilter}
            setShowIncompleteOnly={setShowIncompleteOnly}
            selectAllVisible={selectAllVisible}
            deselectAll={deselectAll}
            clearInstanceFilters={clearInstanceFilters}
            handleRetry={handleRetry}
            handleChangeStage={handleChangeStage}
            handleCancel={handleCancel}
            toggleSelectInstance={toggleSelectInstance}
            openSelectedInstance={openSelectedInstance}
            renderCopyButton={renderCopyButton}
          />
        )}
      </div>

      {selectedInstance && (
        <div data-testid="instance-details-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={closeSelectedInstance}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-4xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(event) => event.stopPropagation()}>
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
                  <button data-testid="instance-retry" onClick={() => handleRetry([selectedInstance.id])} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button data-testid="instance-change-stage" onClick={() => handleChangeStage([selectedInstance.id])} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button data-testid="instance-cancel" onClick={() => handleCancel([selectedInstance.id])} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
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
                    <MermaidDiagram diagram={flowForSelectedInstance.diagram} id={`instance-${selectedInstance.id}`} mermaidLoaded={mermaidLoaded} />
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
                      {visibleHistory.map((event, index) => {
                        const isErrorEvent = event.type === 'Error';
                        const isExpanded = expandedHistoryErrors.has(index);
                        const stageLabel = historyStageLabel(event);

                        return (
                          <tr key={index} className="align-top">
                            <td data-testid={`instance-history-timestamp-${index}`} className="px-4 py-3 text-xs text-zinc-500">{formatDateTime(new Date(event.occurredAt))}</td>
                            <td data-testid={`instance-history-type-${index}`} className="px-4 py-3">
                              <span className={'text-sm font-mono ' + (isErrorEvent ? 'text-red-400' : 'text-zinc-300')}>{event.type}</span>
                            </td>
                            <td data-testid={`instance-history-stage-${index}`} className="px-4 py-3">
                              <span className="text-sm font-mono text-zinc-200">{stageLabel ?? '—'}</span>
                            </td>
                            <td className="px-4 py-3">
                              <div data-testid={`instance-history-details-${index}`} className="text-xs text-zinc-500">{historyDetailsLabel(event)}</div>
                              {isErrorEvent && event.errorStackTrace && (
                                <div className="mt-2">
                                  <button
                                    data-testid={`instance-history-stacktrace-toggle-${index}`}
                                    onClick={() => {
                                      const next = new Set(expandedHistoryErrors);
                                      if (next.has(index)) next.delete(index);
                                      else next.add(index);
                                      setExpandedHistoryErrors(next);
                                    }}
                                    className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors"
                                  >
                                    <ChevronRight size={12} className={'transition-transform ' + (isExpanded ? 'rotate-90' : '')} /> Stack Trace
                                  </button>
                                  {isExpanded && <pre data-testid={`instance-history-stacktrace-${index}`} className="bg-zinc-900/50 rounded p-2 text-xs text-zinc-400 overflow-x-auto font-mono mt-2">{event.errorStackTrace}</pre>}
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
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-5xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(event) => event.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div>
                <h3 data-testid="flow-diagram-title" className="text-lg font-bold text-zinc-50 font-mono">{selectedFlowForDiagram.flowId}</h3>
                <p className="text-sm text-zinc-500 mt-1">Flow Diagram</p>
              </div>
              <button data-testid="flow-diagram-close" onClick={() => setSelectedFlowForDiagram(null)} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 overflow-y-auto flex-1 min-h-0">
              <div className="bg-zinc-800/50 rounded-lg p-8 overflow-auto">
                <MermaidDiagram diagram={selectedFlowForDiagram.diagram} id={`flow-${selectedFlowForDiagram.flowId}`} mermaidLoaded={mermaidLoaded} />
              </div>
            </div>
          </div>
        </div>
      )}

      {showChangeStageModal && (
        <div data-testid="change-stage-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={closeChangeStageModal}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-md" onClick={(event) => event.stopPropagation()}>
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
                <select data-testid="change-stage-select" value={newStage} onChange={(event) => setNewStage(event.target.value)} className="w-full bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-3 text-sm focus:outline-none focus:border-blue-500">
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
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-lg" onClick={(event) => event.stopPropagation()}>
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