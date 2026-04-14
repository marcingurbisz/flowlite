import type { ReactNode } from 'react';
import { ChevronRight, RefreshCw, X } from 'lucide-react';
import { MermaidDiagram } from '../MermaidDiagram';
import { StatusBadge } from '../badges';
import type { FlowDto, HistoryEntryDto, UiInstance } from '../types';
import { formatDateTime, historyDetailsLabel, historyStageLabel } from '../utils';

interface InstanceDetailsModalProps {
  selectedInstance: UiInstance;
  visibleHistory: HistoryEntryDto[];
  latestErrorStackTrace: string | null;
  flowForSelectedInstance: FlowDto | null;
  showDiagram: boolean;
  showStackTrace: boolean;
  expandedHistoryErrors: Set<number>;
  mermaidLoaded: boolean;
  onClose: () => void;
  onRetry: (instanceIds: string[]) => void;
  onChangeStage: (instanceIds: string[]) => void;
  onCancel: (instanceIds: string[]) => void;
  onToggleDiagram: () => void;
  onToggleStackTrace: () => void;
  onToggleHistoryError: (index: number) => void;
  renderCopyButton: (value: string, feedbackKey: string, testId: string) => ReactNode;
}

export const InstanceDetailsModal = ({
  selectedInstance,
  visibleHistory,
  latestErrorStackTrace,
  flowForSelectedInstance,
  showDiagram,
  showStackTrace,
  expandedHistoryErrors,
  mermaidLoaded,
  onClose,
  onRetry,
  onChangeStage,
  onCancel,
  onToggleDiagram,
  onToggleStackTrace,
  onToggleHistoryError,
  renderCopyButton,
}: InstanceDetailsModalProps) => (
  <div data-testid="instance-details-modal" className="fixed inset-0 bg-black/80 backdrop-blur-sm flex items-center justify-center z-50 p-4 overflow-hidden" onClick={onClose}>
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
          {selectedInstance.cockpitStatus === 'Error' && (
            <button data-testid="instance-retry" onClick={() => onRetry([selectedInstance.id])} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
          )}
          {(selectedInstance.cockpitStatus === 'PendingEngine' || selectedInstance.cockpitStatus === 'WaitingForTimer' || selectedInstance.cockpitStatus === 'WaitingForEvent' || selectedInstance.cockpitStatus === 'Error') && (
            <button data-testid="instance-change-stage" onClick={() => onChangeStage([selectedInstance.id])} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
          )}
          {(selectedInstance.cockpitStatus === 'PendingEngine' || selectedInstance.cockpitStatus === 'WaitingForTimer' || selectedInstance.cockpitStatus === 'WaitingForEvent' || selectedInstance.cockpitStatus === 'Error') && (
            <button data-testid="instance-cancel" onClick={() => onCancel([selectedInstance.id])} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
          )}
          <button data-testid="instance-details-close" onClick={onClose} className="p-2 hover:bg-zinc-800 rounded transition-colors"><X size={20} /></button>
        </div>
      </div>

      <div className="p-6 space-y-6 overflow-y-auto flex-1 min-h-0">
        <div>
          <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Overview</h4>
          <div className="grid grid-cols-2 gap-4">
            <div><div className="text-xs text-zinc-500 mb-1">Flow ID</div><div className="text-sm font-mono text-zinc-300">{selectedInstance.flowId}</div></div>
            <div><div className="text-xs text-zinc-500 mb-1">Current Stage</div><div data-testid="instance-details-stage" className="text-sm font-mono text-zinc-300">{selectedInstance.stage || '—'}</div></div>
            <div><div className="text-xs text-zinc-500 mb-1">Status</div><div data-testid="instance-details-status"><StatusBadge status={selectedInstance.cockpitStatus} /></div></div>
            <div><div className="text-xs text-zinc-500 mb-1">Updated At</div><div className="text-sm text-zinc-300">{formatDateTime(selectedInstance.updatedAt)}</div></div>
          </div>
        </div>

        <div>
          <button data-testid="instance-flow-diagram-toggle" onClick={onToggleDiagram} className="flex items-center gap-2 text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3 hover:text-zinc-300 transition-colors">
            <ChevronRight size={16} className={'transition-transform ' + (showDiagram ? 'rotate-90' : '')} /> Flow Diagram
          </button>
          {showDiagram && flowForSelectedInstance && (
            <div className="bg-zinc-800/50 rounded-lg p-6 overflow-auto max-h-96">
              <MermaidDiagram diagram={flowForSelectedInstance.diagram} id={`instance-${selectedInstance.id}`} mermaidLoaded={mermaidLoaded} />
            </div>
          )}
        </div>

        {selectedInstance.cockpitStatus === 'Error' && (
          <div>
            <h4 className="text-sm font-bold text-zinc-400 uppercase tracking-wide mb-3">Error Information</h4>
            <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 space-y-3">
              <div><div className="text-xs text-zinc-500 mb-2">Error Message</div><div className="text-sm text-zinc-300">{selectedInstance.errorMessage}</div></div>
              {latestErrorStackTrace && (
                <div>
                  <button data-testid="instance-error-stacktrace-toggle" onClick={onToggleStackTrace} className="flex items-center gap-2 text-xs text-zinc-400 hover:text-zinc-300 transition-colors mb-2">
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
                              onClick={() => onToggleHistoryError(index)}
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
);