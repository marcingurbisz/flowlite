import type { ReactNode } from 'react';
import { ChevronRight, Database, RefreshCw, Search, X } from 'lucide-react';
import { StatusBadge } from '../badges';
import type { StatusFilter, UiInstance } from '../types';
import { formatDateTime } from '../utils';

export const InstancesView = ({
  searchTerm,
  statusFilter,
  stageFilter,
  errorMessageFilter,
  showIncompleteOnly,
  selectedInstances,
  hasInstanceFiltersApplied,
  filteredInstances,
  setSearchTerm,
  setStatusFilter,
  setStageFilter,
  setErrorMessageFilter,
  setShowIncompleteOnly,
  selectAllVisible,
  deselectAll,
  clearInstanceFilters,
  handleRetry,
  handleChangeStage,
  handleCancel,
  toggleSelectInstance,
  openSelectedInstance,
  renderCopyButton,
}: {
  searchTerm: string;
  statusFilter: StatusFilter;
  stageFilter: string;
  errorMessageFilter: string;
  showIncompleteOnly: boolean;
  selectedInstances: Set<string>;
  hasInstanceFiltersApplied: boolean;
  filteredInstances: UiInstance[];
  setSearchTerm: (value: string) => void;
  setStatusFilter: (value: StatusFilter) => void;
  setStageFilter: (value: string) => void;
  setErrorMessageFilter: (value: string) => void;
  setShowIncompleteOnly: (value: boolean) => void;
  selectAllVisible: () => void;
  deselectAll: () => void;
  clearInstanceFilters: () => void;
  handleRetry: (instanceIds: string[]) => void;
  handleChangeStage: (instanceIds: string[]) => void;
  handleCancel: (instanceIds: string[]) => void;
  toggleSelectInstance: (instanceId: string) => void;
  openSelectedInstance: (instance: UiInstance) => void;
  renderCopyButton: (value: string, feedbackKey: string, testId: string) => ReactNode;
}) => (
  <div className="space-y-4">
    <div className="flex items-center gap-4 mb-6">
      <div className="flex-1 relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-500" size={16} />
        <input
          data-testid="instances-search"
          type="text"
          placeholder="Search by instance ID or flow ID..."
          value={searchTerm}
          onChange={(event) => setSearchTerm(event.target.value)}
          className="w-full bg-zinc-900 border border-zinc-800 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
        />
      </div>
      <select
        data-testid="instances-status-filter"
        value={statusFilter}
        onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}
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
        onChange={(event) => setStageFilter(event.target.value || 'all')}
        className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
      />
      <input
        data-testid="instances-error-filter"
        type="text"
        placeholder="Filter by error message..."
        value={errorMessageFilter}
        onChange={(event) => setErrorMessageFilter(event.target.value)}
        className="flex-1 bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
      />
      <label className="inline-flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2 text-sm text-zinc-300">
        <input
          data-testid="instances-incomplete-only"
          type="checkbox"
          checked={showIncompleteOnly}
          onChange={(event) => setShowIncompleteOnly(event.target.checked)}
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
          <button data-testid="instances-retry-selected" onClick={() => handleRetry(Array.from(selectedInstances))} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><RefreshCw size={14} />Retry</button>
          <button data-testid="instances-change-stage-selected" onClick={() => handleChangeStage(Array.from(selectedInstances))} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><ChevronRight size={14} />Change Stage</button>
          <button data-testid="instances-cancel-selected" onClick={() => handleCancel(Array.from(selectedInstances))} className="px-4 py-2 bg-red-600 hover:bg-red-700 rounded transition-colors text-sm font-medium flex items-center gap-2"><X size={14} />Cancel</button>
        </div>
      </div>
    )}

    {!hasInstanceFiltersApplied ? (
      <div data-testid="instances-apply-filters" className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
        <Database size={48} className="mx-auto mb-4 text-zinc-600" />
        <p className="text-lg font-medium text-zinc-300 mb-2">Apply filters to view instances</p>
        <p className="text-sm text-zinc-500">The Instances tab now waits for a search or filter before requesting rows.</p>
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
                <td className="px-4 py-3" onClick={(event) => event.stopPropagation()}>
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
    )}
  </div>
);