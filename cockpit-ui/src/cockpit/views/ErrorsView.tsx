import type { ReactNode } from 'react';
import { CheckCircle, ChevronRight, RefreshCw, X } from 'lucide-react';
import type { ErrorGroupDto, FlowDto, UiInstance } from '../types';
import { toTestIdFragment } from '../utils';

export const ErrorsView = ({
  flows,
  isLoading,
  filteredErrorGroups,
  instances,
  selectedInstances,
  errorFlowFilter,
  errorStageFilter,
  errorMessageFilterErrors,
  setErrorFlowFilter,
  setErrorStageFilter,
  setErrorMessageFilterErrors,
  clearErrorFilters,
  deselectAll,
  toggleSelectInstance,
  selectAllErrorsInGroup,
  deselectErrorsInGroup,
  openSelectedInstance,
  handleRetry,
  handleChangeStage,
  handleCancel,
  renderCopyButton,
}: {
  flows: FlowDto[];
  isLoading: boolean;
  filteredErrorGroups: ErrorGroupDto[];
  instances: UiInstance[];
  selectedInstances: Set<string>;
  errorFlowFilter: string;
  errorStageFilter: string;
  errorMessageFilterErrors: string;
  setErrorFlowFilter: (value: string) => void;
  setErrorStageFilter: (value: string) => void;
  setErrorMessageFilterErrors: (value: string) => void;
  clearErrorFilters: () => void;
  deselectAll: () => void;
  toggleSelectInstance: (instanceId: string) => void;
  selectAllErrorsInGroup: (instanceIds: string[]) => void;
  deselectErrorsInGroup: (instanceIds: string[]) => void;
  openSelectedInstance: (instance: UiInstance) => void;
  handleRetry: (instanceIds: string[]) => void;
  handleChangeStage: (instanceIds: string[]) => void;
  handleCancel: (instanceIds: string[]) => void;
  renderCopyButton: (value: string, feedbackKey: string, testId: string) => ReactNode;
}) => (
  <div className="space-y-4">
    <div className="flex items-center gap-4 mb-6">
      <select
        data-testid="errors-flow-filter"
        value={errorFlowFilter}
        onChange={(event) => setErrorFlowFilter(event.target.value)}
        className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
      >
        <option value="all">All Flows</option>
        {flows.map((flow) => (
          <option key={flow.flowId} value={flow.flowId}>{flow.flowId}</option>
        ))}
      </select>
      <input
        data-testid="errors-stage-filter"
        type="text"
        placeholder="Filter by stage..."
        value={errorStageFilter === 'all' ? '' : errorStageFilter}
        onChange={(event) => setErrorStageFilter(event.target.value || 'all')}
        className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
      />
      <input
        data-testid="errors-message-filter"
        type="text"
        placeholder="Filter by error message..."
        value={errorMessageFilterErrors}
        onChange={(event) => setErrorMessageFilterErrors(event.target.value)}
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

    {isLoading ? (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
        <p className="text-lg font-medium text-zinc-300 mb-2">Loading errors…</p>
        <p className="text-sm text-zinc-500">Waiting for the filtered error dataset from the backend.</p>
      </div>
    ) : (
      <>
    {selectedInstances.size > 0 && (
      <div data-testid="errors-selection-bar" className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
        <span className="text-sm text-emerald-400">{selectedInstances.size} error(s) selected</span>
        <div className="flex gap-2">
          <button
            data-testid="errors-retry-selected"
            onClick={() => handleRetry(Array.from(selectedInstances))}
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
            onClick={() => handleCancel(Array.from(selectedInstances))}
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
      filteredErrorGroups.map((group, index) => {
        const groupInstances = instances.filter(
          (instance) => instance.flowId === group.flowId && instance.stage === (group.stage ?? '') && instance.status === 'Error',
        );
        const groupTestIdSuffix = `${group.flowId}-${toTestIdFragment(group.stage)}`;

        return (
          <div key={index} data-testid={`error-group-${group.flowId}-${toTestIdFragment(group.stage)}`} className="bg-zinc-900 border border-red-900/30 rounded-lg p-6">
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
                      onClick={(event) => event.stopPropagation()}
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
      </>
    )}
  </div>
);