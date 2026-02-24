import { useEffect, useMemo, useRef, useState } from 'react';
import { Play, AlertCircle, CheckCircle, Clock, RefreshCw, ChevronRight, X, Search, Database } from 'lucide-react';

type InstanceStatus = 'Pending' | 'Running' | 'Completed' | 'Error' | 'Cancelled';
type HistoryEventType = 'Started' | 'EventAppended' | 'StatusChanged' | 'StageChanged' | 'Cancelled' | 'Error';

interface FlowDto {
  flowId: string;
  diagram: string;
  stages: string[];
  notCompletedCount: number;
  errorCount: number;
  activeCount: number;
  completedCount: number;
}

interface InstanceDto {
  flowId: string;
  flowInstanceId: string;
  stage: string | null;
  status: InstanceStatus | null;
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

const statusConfig: Record<InstanceStatus, { bg: string; text: string; label: string }> = {
  Pending: { bg: 'bg-amber-500/20', text: 'text-amber-400', label: 'Pending' },
  Running: { bg: 'bg-blue-500/20', text: 'text-blue-400', label: 'Running' },
  Completed: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', label: 'Completed' },
  Error: { bg: 'bg-red-500/20', text: 'text-red-400', label: 'Error' },
  Cancelled: { bg: 'bg-zinc-500/20', text: 'text-zinc-400', label: 'Cancelled' },
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

const FlowLiteCockpit = () => {
  const [activeView, setActiveView] = useState<'flows' | 'errors' | 'long-running' | 'instances'>('flows');
  const [flows, setFlows] = useState<FlowDto[]>([]);
  const [instances, setInstances] = useState<UiInstance[]>([]);
  const [errorsByGroup, setErrorsByGroup] = useState<ErrorGroupDto[]>([]);
  const [selectedInstance, setSelectedInstance] = useState<UiInstance | null>(null);
  const [selectedFlowForDiagram, setSelectedFlowForDiagram] = useState<FlowDto | null>(null);
  const [instanceHistory, setInstanceHistory] = useState<HistoryEntryDto[]>([]);

  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | InstanceStatus>('all');
  const [stageFilter, setStageFilter] = useState('all');
  const [errorMessageFilter, setErrorMessageFilter] = useState('');
  const [showIncompleteOnly, setShowIncompleteOnly] = useState(false);
  const [errorFlowFilter, setErrorFlowFilter] = useState('all');
  const [errorStageFilter, setErrorStageFilter] = useState('all');
  const [errorMessageFilterErrors, setErrorMessageFilterErrors] = useState('');
  const [longRunningFlowFilter, setLongRunningFlowFilter] = useState('all');
  const [selectedInstances, setSelectedInstances] = useState<Set<string>>(new Set());
  const [showDiagram, setShowDiagram] = useState(false);
  const [showStackTrace, setShowStackTrace] = useState(false);
  const [expandedHistoryErrors, setExpandedHistoryErrors] = useState<Set<number>>(new Set());
  const [mermaidLoaded, setMermaidLoaded] = useState(false);
  const [longRunningThresholdHours, setLongRunningThresholdHours] = useState(1);
  const [showChangeStageModal, setShowChangeStageModal] = useState(false);
  const [changeStageTargetInstances, setChangeStageTargetInstances] = useState<string[]>([]);
  const [newStage, setNewStage] = useState('');

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

  const refreshData = async () => {
    const [flowRows, allRows, errorRows] = await Promise.all([
      apiGet<FlowDto[]>('/api/flows'),
      apiGet<InstanceDto[]>('/api/instances'),
      apiGet<ErrorGroupDto[]>('/api/errors'),
    ]);

    setFlows(flowRows);
    setErrorsByGroup(errorRows);
    setInstances(
      allRows
        .filter((it) => it.status !== null)
        .map((it) => ({
          id: it.flowInstanceId,
          flowId: it.flowId,
          stage: it.stage ?? '',
          status: it.status as InstanceStatus,
          updatedAt: new Date(it.lastUpdatedAt),
          createdAt: new Date(it.lastUpdatedAt),
          errorMessage: it.lastErrorMessage,
        })),
    );
  };

  useEffect(() => {
    void refreshData();
  }, []);

  useEffect(() => {
    if (!selectedInstance) {
      setInstanceHistory([]);
      return;
    }

    void apiGet<HistoryEntryDto[]>(`/api/instances/${encodeURIComponent(selectedInstance.flowId)}/${encodeURIComponent(selectedInstance.id)}/timeline`)
      .then(setInstanceHistory)
      .catch((e) => {
        console.error(e);
        setInstanceHistory([]);
      });
  }, [selectedInstance]);

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
  }, [selectedInstance]);

  useEffect(() => {
    document.body.style.overflow = selectedInstance || selectedFlowForDiagram ? 'hidden' : 'unset';
    return () => {
      document.body.style.overflow = 'unset';
    };
  }, [selectedInstance, selectedFlowForDiagram]);

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
    const thresholdMs = longRunningThresholdHours * 60 * 60 * 1000;
    const now = Date.now();

    return instances
      .filter((i) => i.status === 'Running')
      .filter((i) => i.status === 'Running')
      .filter((i) => longRunningFlowFilter === 'all' || i.flowId === longRunningFlowFilter)
      .map((instance) => ({
        ...instance,
        runningDuration: now - instance.updatedAt.getTime(),
        runningHours: (now - instance.updatedAt.getTime()) / (60 * 60 * 1000),
      }))
      .filter((instance) => instance.runningDuration > thresholdMs)
      .sort((a, b) => b.runningDuration - a.runningDuration);
  }, [instances, longRunningThresholdHours, longRunningFlowFilter]);

  const toggleSelectInstance = (instanceId: string) => {
    const next = new Set(selectedInstances);
    if (next.has(instanceId)) next.delete(instanceId);
    else next.add(instanceId);
    setSelectedInstances(next);
  };

  const selectAllVisible = () => {
    const actionable = filteredInstances.filter((i) => i.status === 'Pending' || i.status === 'Error').map((i) => i.id);
    setSelectedInstances(new Set(actionable));
  };

  const deselectAll = () => setSelectedInstances(new Set());

  const handleRetry = async (instanceIds: string[]) => {
    const selected = instances.filter((i) => instanceIds.includes(i.id));
    await Promise.all(
      selected.map((i) => apiPost(`/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}/retry`)),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const handleCancel = async (instanceIds: string[]) => {
    const selected = instances.filter((i) => instanceIds.includes(i.id));
    await Promise.all(
      selected.map((i) => apiPost(`/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}/cancel`)),
    );
    setSelectedInstances(new Set());
    await refreshData();
  };

  const handleChangeStage = (instanceIds: string[]) => {
    setChangeStageTargetInstances(instanceIds);
    setShowChangeStageModal(true);
  };

  const confirmChangeStage = async () => {
    const selected = instances.filter((i) => changeStageTargetInstances.includes(i.id));
    await Promise.all(
      selected.map((i) =>
        apiPost(
          `/api/instances/${encodeURIComponent(i.flowId)}/${encodeURIComponent(i.id)}/change-stage?stage=${encodeURIComponent(newStage)}`,
        ),
      ),
    );

    setShowChangeStageModal(false);
    setChangeStageTargetInstances([]);
    setNewStage('');
    setSelectedInstances(new Set());
    await refreshData();
  };

  const visibleHistory = instanceHistory;
  const latestErrorStackTrace =
    visibleHistory.find((h) => h.type === 'Error' && h.errorStackTrace)?.errorStackTrace ?? null;

  const flowForSelectedInstance = selectedInstance ? flows.find((f) => f.flowId === selectedInstance.flowId) : null;

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100" style={{ fontFamily: '"IBM Plex Mono", monospace' }}>
      <header className="border-b border-zinc-800 bg-zinc-900/50 backdrop-blur">
        <div className="max-w-7xl mx-auto px-6 py-4">
          <h1 className="text-2xl font-bold tracking-tight text-zinc-50">FlowLite Cockpit</h1>
          <p className="text-sm text-zinc-500 mt-1">Workflow Engine Monitoring & Management</p>
          <p className="text-xs text-zinc-600 mt-1">
            flows: {stats.totalFlows} • instances: {stats.totalInstances} • errors: {stats.errorInstances}
          </p>
        </div>
      </header>

      <div className="max-w-7xl mx-auto px-6">
        <div className="border-b border-zinc-800">
          <div className="flex gap-6">
            {['flows', 'errors', 'long-running', 'instances'].map((view) => (
              <button
                key={view}
                onClick={() => setActiveView(view as 'flows' | 'errors' | 'long-running' | 'instances')}
                className={
                  'px-1 py-3 text-sm font-medium border-b-2 transition-colors ' +
                  (activeView === view
                    ? 'border-emerald-500 text-emerald-400'
                    : 'border-transparent text-zinc-500 hover:text-zinc-300')
                }
              >
                {view === 'long-running' ? 'Long Running' : view.charAt(0).toUpperCase() + view.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-6 py-6">
        {activeView === 'flows' && (
          <div className="space-y-4">
            <h2 className="text-xl font-bold text-zinc-50 mb-4">Flow Definitions</h2>
            {flows.map((flow) => {
              const incompleteInstances = instances.filter(
                (i) => i.flowId === flow.flowId && i.status !== 'Completed' && i.status !== 'Cancelled',
              );

              const thresholdMs = longRunningThresholdHours * 60 * 60 * 1000;
              const now = Date.now();
              const longRunningForFlow = instances.filter(
                (i) => i.flowId === flow.flowId && i.status === 'Running' && now - i.updatedAt.getTime() > thresholdMs,
              ).length;

              const stageBreakdown: Record<string, { total: number; errors: number }> = {};
              incompleteInstances.forEach((instance) => {
                if (!instance.stage) return;
                if (!stageBreakdown[instance.stage]) stageBreakdown[instance.stage] = { total: 0, errors: 0 };
                stageBreakdown[instance.stage].total++;
                if (instance.status === 'Error') stageBreakdown[instance.stage].errors++;
              });

              return (
                <div key={flow.flowId} className="bg-zinc-900 border border-zinc-800 rounded-lg p-6">
                  <div className="flex items-start justify-between mb-4">
                    <div>
                      <h3 className="text-lg font-bold text-zinc-50 font-mono">{flow.flowId}</h3>
                      <p className="text-xs text-zinc-500 mt-1">{flow.stages.length} stages</p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => setSelectedFlowForDiagram(flow)}
                        className="px-3 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-xs transition-colors"
                      >
                        View Diagram
                      </button>
                      {longRunningForFlow > 0 && (
                        <button
                          onClick={() => {
                            setActiveView('long-running');
                            setLongRunningFlowFilter(flow.flowId);
                          }}
                          className="px-3 py-1 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded text-xs font-mono transition-colors"
                        >
                          {longRunningForFlow} long running ⚠
                        </button>
                      )}
                      <button
                        onClick={() => {
                          setActiveView('instances');
                          setSearchTerm(flow.flowId);
                          setStatusFilter('all');
                          setStageFilter('all');
                          setErrorMessageFilter('');
                          setShowIncompleteOnly(true);
                        }}
                        className="px-3 py-1 bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 rounded text-xs font-mono transition-colors"
                      >
                        {incompleteInstances.length} incomplete →
                      </button>
                    </div>
                  </div>

                  {Object.keys(stageBreakdown).length > 0 && (
                    <div className="mb-4">
                      <h4 className="text-xs font-bold text-zinc-500 uppercase tracking-wide mb-2">Active Stages</h4>
                      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2">
                        {Object.entries(stageBreakdown).map(([stage, data]) => (
                          <button
                            key={stage}
                            onClick={() => {
                              setActiveView('instances');
                              setSearchTerm(flow.flowId);
                              setStatusFilter('all');
                              setStageFilter(stage);
                            }}
                            className="bg-zinc-800/50 hover:bg-zinc-800 rounded p-3 text-left transition-colors group"
                          >
                            <div className="text-xs text-zinc-400 mb-1 truncate group-hover:text-zinc-300">{stage}</div>
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-bold text-zinc-200">{data.total}</span>
                              {data.errors > 0 && (
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setActiveView('errors');
                                    setErrorFlowFilter(flow.flowId);
                                    setErrorStageFilter(stage);
                                  }}
                                  className="text-xs px-1.5 py-0.5 bg-red-500/20 hover:bg-red-500/30 text-red-400 rounded font-mono transition-colors"
                                >
                                  {data.errors} err
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
                type="text"
                placeholder="Filter by stage..."
                value={errorStageFilter === 'all' ? '' : errorStageFilter}
                onChange={(e) => setErrorStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilterErrors}
                onChange={(e) => setErrorMessageFilterErrors(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
            </div>

            {selectedInstances.size > 0 && (
              <div className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                <span className="text-sm text-emerald-400">{selectedInstances.size} error(s) selected</span>
                <div className="flex gap-2">
                  <button
                    onClick={() => void handleRetry(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <RefreshCw size={14} /> Retry Selected ({selectedInstances.size})
                  </button>
                  <button
                    onClick={() => handleChangeStage(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <ChevronRight size={14} /> Change Stage ({selectedInstances.size})
                  </button>
                  <button
                    onClick={() => void handleCancel(Array.from(selectedInstances))}
                    className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"
                  >
                    <X size={14} /> Cancel Selected ({selectedInstances.size})
                  </button>
                </div>
              </div>
            )}

            {filteredErrorGroups.length === 0 ? (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                <p className="text-lg font-medium text-zinc-300 mb-2">No errors match filters</p>
              </div>
            ) : (
              filteredErrorGroups.map((group, idx) => {
                const groupInstances = instances.filter(
                  (i) => i.flowId === group.flowId && i.stage === (group.stage ?? '') && i.status === 'Error',
                );

                return (
                  <div key={idx} className="bg-zinc-900 border border-red-900/30 rounded-lg p-6">
                    <div className="flex items-start justify-between mb-4">
                      <div>
                        <h3 className="text-lg font-bold text-zinc-50 font-mono">{group.flowId}</h3>
                        <p className="text-sm text-zinc-500 mt-1">Stage: <span className="font-mono">{group.stage}</span></p>
                      </div>
                      <div className="px-3 py-1 bg-red-500/20 text-red-400 rounded text-xs font-mono">{group.count} errors</div>
                    </div>

                    <div className="space-y-2 mt-4">
                      {groupInstances.map((instance) => (
                        <div
                          key={instance.id}
                          className="bg-zinc-800/50 rounded p-3 hover:bg-zinc-800 transition-colors cursor-pointer"
                          onClick={() => setSelectedInstance(instance)}
                        >
                          <div className="flex items-start gap-3">
                            <input
                              type="checkbox"
                              checked={selectedInstances.has(instance.id)}
                              onChange={() => toggleSelectInstance(instance.id)}
                              onClick={(e) => e.stopPropagation()}
                              className="w-4 h-4 rounded border-zinc-600 bg-zinc-700 mt-1 flex-shrink-0"
                            />
                            <div className="flex-1 min-w-0">
                              <div className="text-sm font-mono text-zinc-300">{instance.id}</div>
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
                <h2 className="text-xl font-bold text-zinc-50">Long Running Instances</h2>
                <p className="text-sm text-zinc-500 mt-1">Instances in RUNNING state longer than threshold</p>
              </div>
              <div className="flex items-center gap-3">
                <select
                  value={longRunningFlowFilter}
                  onChange={(e) => setLongRunningFlowFilter(e.target.value)}
                  className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                >
                  <option value="all">All Flows</option>
                  {flows.map((flow) => (
                    <option key={flow.flowId} value={flow.flowId}>{flow.flowId}</option>
                  ))}
                </select>
                <label className="text-sm text-zinc-400">Threshold:</label>
                <input
                  type="number"
                  min="0.1"
                  step="0.5"
                  value={longRunningThresholdHours}
                  onChange={(e) => setLongRunningThresholdHours(parseFloat(e.target.value) || 1)}
                  className="w-20 bg-zinc-900 border border-zinc-800 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
                <span className="text-sm text-zinc-400">hours</span>
              </div>
            </div>

            {longRunningInstances.length === 0 ? (
              <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
                <CheckCircle size={48} className="mx-auto mb-4 text-emerald-500 opacity-50" />
                <p className="text-lg font-medium text-zinc-300 mb-2">No long running instances</p>
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
                      <th className="px-4 py-3 font-medium">Running Duration</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-zinc-800">
                    {longRunningInstances.map((instance) => {
                      const hours = Math.floor(instance.runningHours);
                      const minutes = Math.floor((instance.runningHours - hours) * 60);
                      const durationText = hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;

                      return (
                        <tr key={instance.id} className="hover:bg-zinc-800/30 transition-colors cursor-pointer" onClick={() => setSelectedInstance(instance)}>
                          <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                            <input
                              type="checkbox"
                              checked={selectedInstances.has(instance.id)}
                              onChange={() => toggleSelectInstance(instance.id)}
                              className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                            />
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.id}</td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                          <td className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage}</td>
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
                  type="text"
                  placeholder="Search by instance ID or flow ID..."
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
                />
              </div>
              <select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value as 'all' | InstanceStatus);
                  if (e.target.value !== 'all') setShowIncompleteOnly(false);
                }}
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
                type="text"
                placeholder="Filter by stage..."
                value={stageFilter === 'all' ? '' : stageFilter}
                onChange={(e) => setStageFilter(e.target.value || 'all')}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <input
                type="text"
                placeholder="Filter by error message..."
                value={errorMessageFilter}
                onChange={(e) => setErrorMessageFilter(e.target.value)}
                className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
              />
              <button onClick={selectAllVisible} className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors">Select All Actionable</button>
              <button onClick={deselectAll} className="px-3 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm transition-colors">Deselect</button>
            </div>

            {selectedInstances.size > 0 && (
              <div className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
                <span className="text-sm text-emerald-400">{selectedInstances.size} instance(s) selected</span>
                <div className="flex gap-2">
                  <button onClick={() => void handleRetry(Array.from(selectedInstances))} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
                  <button onClick={() => handleChangeStage(Array.from(selectedInstances))} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
                  <button onClick={() => void handleCancel(Array.from(selectedInstances))} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
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
                    <tr key={instance.id} className="hover:bg-zinc-800/30 transition-colors cursor-pointer" onClick={() => setSelectedInstance(instance)}>
                      <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                        {(instance.status === 'Pending' || instance.status === 'Error') && (
                          <input
                            type="checkbox"
                            checked={selectedInstances.has(instance.id)}
                            onChange={() => toggleSelectInstance(instance.id)}
                            className="w-4 h-4 rounded border-zinc-600 bg-zinc-700"
                          />
                        )}
                      </td>
                      <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.id}</td>
                      <td className="px-4 py-3 font-mono text-xs text-zinc-300">{instance.flowId}</td>
                      <td className="px-4 py-3 font-mono text-xs text-zinc-400">{instance.stage || '—'}</td>
                      <td className="px-4 py-3"><StatusBadge status={instance.status} /></td>
                      <td className="px-4 py-3 text-xs text-zinc-500">{instance.updatedAt.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {selectedInstance && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={() => setSelectedInstance(null)}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-4xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div className="flex-1">
                <h3 className="text-lg font-bold text-zinc-50">Instance Details</h3>
                <p className="text-sm text-zinc-500 font-mono mt-1">{selectedInstance.id}</p>
              </div>
              <div className="flex items-center gap-2">
                {selectedInstance.status === 'Error' && (
                  <button onClick={() => void handleRetry([selectedInstance.id])} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button onClick={() => handleChangeStage([selectedInstance.id])} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
                )}
                {(selectedInstance.status === 'Pending' || selectedInstance.status === 'Error') && (
                  <button onClick={() => void handleCancel([selectedInstance.id])} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
                )}
                <button onClick={() => setSelectedInstance(null)} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
              </div>
            </div>

            <div className="p-6 space-y-6 overflow-y-auto flex-1 min-h-0">
              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Overview</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><div className="text-xs text-zinc-500 mb-1">Flow ID</div><div className="text-sm font-mono text-zinc-300">{selectedInstance.flowId}</div></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Current Stage</div><div className="text-sm font-mono text-zinc-300">{selectedInstance.stage || '—'}</div></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Status</div><StatusBadge status={selectedInstance.status} /></div>
                  <div><div className="text-xs text-zinc-500 mb-1">Updated At</div><div className="text-sm text-zinc-300">{selectedInstance.updatedAt.toLocaleString()}</div></div>
                </div>
              </div>

              <div>
                <button onClick={() => setShowDiagram(!showDiagram)} className="flex items-center gap-2 text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3 hover:text-zinc-300 transition-colors">
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
                        <button onClick={() => setShowStackTrace(!showStackTrace)} className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors mb-2">
                          <ChevronRight size={14} className={'transition-transform ' + (showStackTrace ? 'rotate-90' : '')} /> Stack Trace
                        </button>
                        {showStackTrace && <pre className="bg-zinc-900/50 rounded p-3 text-xs text-zinc-400 overflow-x-auto font-mono">{latestErrorStackTrace}</pre>}
                      </div>
                    )}
                  </div>
                </div>
              )}

              <div>
                <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Event History</h4>
                <div className="space-y-3">
                  {visibleHistory.map((event, idx) => {
                    const isErrorEvent = event.type === 'Error';
                    const isExpanded = expandedHistoryErrors.has(idx);

                    return (
                      <div key={idx} className="flex gap-4">
                        <div className="text-xs text-zinc-500 w-40 flex-shrink-0">{new Date(event.occurredAt).toLocaleTimeString()}</div>
                        <div className="flex-1">
                          <div className={'text-sm font-mono ' + (isErrorEvent ? 'text-red-400' : 'text-zinc-300')}>{event.type}</div>
                          <div className="text-xs text-zinc-500 mt-1">{event.stage ?? event.toStage ?? '—'}</div>
                          <div className="text-xs text-zinc-500 mt-1">
                            {[event.event ? `event=${event.event}` : null, event.fromStage || event.toStage ? `stage: ${event.fromStage ?? ''} -> ${event.toStage ?? ''}` : null, event.fromStatus || event.toStatus ? `status: ${event.fromStatus ?? ''} -> ${event.toStatus ?? ''}` : null, event.errorMessage ? `error=${event.errorMessage}` : null].filter(Boolean).join(' • ')}
                          </div>
                          {isErrorEvent && event.errorStackTrace && (
                            <div className="mt-2">
                              <button
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
                              {isExpanded && <pre className="bg-zinc-900/50 rounded p-2 text-xs text-zinc-400 overflow-x-auto font-mono mt-2">{event.errorStackTrace}</pre>}
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {selectedFlowForDiagram && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={() => setSelectedFlowForDiagram(null)}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-5xl flex flex-col" style={{ maxHeight: 'calc(100vh - 2rem)' }} onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between flex-shrink-0">
              <div>
                <h3 className="text-lg font-bold text-zinc-50 font-mono">{selectedFlowForDiagram.flowId}</h3>
                <p className="text-sm text-zinc-500 mt-1">Flow Diagram</p>
              </div>
              <button onClick={() => setSelectedFlowForDiagram(null)} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 overflow-y-auto flex-1 min-h-0"><div className="bg-zinc-800/50 rounded-lg p-8 overflow-auto"><MermaidDiagram diagram={selectedFlowForDiagram.diagram} id={`flow-${selectedFlowForDiagram.flowId}`} /></div></div>
          </div>
        </div>
      )}

      {showChangeStageModal && (
        <div className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4" onClick={() => { setShowChangeStageModal(false); setChangeStageTargetInstances([]); setNewStage(''); }}>
          <div className="bg-zinc-900 border border-zinc-800 rounded-lg w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <div className="bg-zinc-900 border-b border-zinc-800 p-6 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-zinc-50">Change Stage</h3>
                <p className="text-sm text-zinc-500 mt-1">{changeStageTargetInstances.length} instance(s) selected</p>
              </div>
              <button onClick={() => { setShowChangeStageModal(false); setChangeStageTargetInstances([]); setNewStage(''); }} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="text-sm font-medium text-zinc-400 mb-2 block">Select New Stage</label>
                <select value={newStage} onChange={(e) => setNewStage(e.target.value)} className="w-full bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-3 text-sm focus:outline-none focus:border-blue-500">
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
                <button onClick={() => { setShowChangeStageModal(false); setChangeStageTargetInstances([]); setNewStage(''); }} className="px-4 py-2 bg-zinc-800 hover:bg-zinc-700 rounded transition-colors text-sm font-medium">Cancel</button>
                <button onClick={() => void confirmChangeStage()} disabled={!newStage} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-zinc-700 disabled:text-zinc-500 disabled:cursor-not-allowed rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default FlowLiteCockpit;
