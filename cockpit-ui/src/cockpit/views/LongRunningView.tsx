import type { ReactNode } from 'react';
import { CheckCircle, RefreshCw } from 'lucide-react';
import { StatusBadge } from '../badges';
import type { FlowDto, LongRunningStatusFilter, UiInstance } from '../types';
import { formatElapsedDuration } from '../utils';

type LongRunningInstance = UiInstance & { inactiveDuration: number };

export const LongRunningView = ({
  flows,
  isLoading,
  totalCount,
  longRunningFlowFilter,
  longRunningStatusFilter,
  longRunningThreshold,
  longRunningInstances,
  selectedLongRunningIds,
  selectedInstances,
  setLongRunningFlowFilter,
  setLongRunningStatusFilter,
  setLongRunningThreshold,
  deselectAll,
  toggleSelectInstance,
  openSelectedInstance,
  handleRetry,
  renderCopyButton,
}: {
  flows: FlowDto[];
  isLoading: boolean;
  totalCount: number;
  longRunningFlowFilter: string;
  longRunningStatusFilter: LongRunningStatusFilter;
  longRunningThreshold: string;
  longRunningInstances: LongRunningInstance[];
  selectedLongRunningIds: string[];
  selectedInstances: Set<string>;
  setLongRunningFlowFilter: (value: string) => void;
  setLongRunningStatusFilter: (value: LongRunningStatusFilter) => void;
  setLongRunningThreshold: (value: string) => void;
  deselectAll: () => void;
  toggleSelectInstance: (instanceId: string) => void;
  openSelectedInstance: (instance: UiInstance) => void;
  handleRetry: (instanceIds: string[]) => void;
  renderCopyButton: (value: string, feedbackKey: string, testId: string) => ReactNode;
}) => (
  <div className="space-y-4">
    <div className="flex items-center justify-between mb-6">
      <div>
        <h2 data-testid="long-running-heading" className="text-xl font-bold text-zinc-50">Long Inactive Instances</h2>
        <p className="text-sm text-zinc-500 mt-1">Instances with no stage or status change beyond the threshold. By default this includes only Running and Pending engine rows.</p>
      </div>
      <div className="flex items-center gap-3">
        <select
          data-testid="long-running-flow-filter"
          value={longRunningFlowFilter}
          onChange={(event) => setLongRunningFlowFilter(event.target.value)}
          className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
        >
          <option value="all">All Flows</option>
          {flows.map((flow) => (
            <option key={flow.flowId} value={flow.flowId}>{flow.flowId}</option>
          ))}
        </select>
        <select
          data-testid="long-running-status-filter"
          value={longRunningStatusFilter}
          onChange={(event) => setLongRunningStatusFilter(event.target.value as LongRunningStatusFilter)}
          className="bg-zinc-900 border border-zinc-800 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-emerald-500"
        >
          <option value="default">Running + pending engine</option>
          <option value="all">All activity kinds</option>
          <option value="Running">Running</option>
          <option value="PendingEngine">Pending engine</option>
          <option value="WaitingForTimer">Waiting for timer</option>
          <option value="WaitingForEvent">Waiting for event</option>
        </select>
        <label className="text-sm text-zinc-400">Threshold:</label>
        <input
          data-testid="long-running-threshold"
          type="text"
          value={longRunningThreshold}
          onChange={(event) => setLongRunningThreshold(event.target.value)}
          className="w-28 bg-zinc-900 border border-zinc-800 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-emerald-500"
        />
        <span className="text-xs text-zinc-500">Examples: 30s, 1m, 1h 30m</span>
      </div>
    </div>

    {!isLoading && (
      <p data-testid="long-running-result-count" className="text-xs text-zinc-500 -mt-3 mb-3">
        returned by backend: {totalCount}
      </p>
    )}

    {isLoading ? (
      <div className="bg-zinc-900 border border-zinc-800 rounded-lg p-12 text-center">
        <p className="text-lg font-medium text-zinc-300 mb-2">Loading long inactive instances…</p>
        <p className="text-sm text-zinc-500">Waiting for the filtered inactive-instance dataset from the backend.</p>
      </div>
    ) : (
      <>
    {selectedLongRunningIds.length > 0 && (
      <div data-testid="long-running-selection-bar" className="flex items-center justify-between p-4 bg-emerald-500/10 border border-emerald-500/30 rounded-lg mb-4">
        <span className="text-sm text-emerald-400">{selectedLongRunningIds.length} long inactive instance(s) selected</span>
        <div className="flex gap-2">
          <button
            data-testid="long-running-retry-selected"
            onClick={() => handleRetry(selectedLongRunningIds)}
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
              <th className="px-4 py-3 font-medium">Inactive Duration</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800">
            {longRunningInstances.map((instance) => (
              <tr
                key={instance.id}
                data-testid={`long-running-row-${instance.id}`}
                className="hover:bg-zinc-800/30 transition-colors cursor-pointer"
                onClick={() => openSelectedInstance(instance)}
              >
                <td className="px-4 py-3" onClick={(event) => event.stopPropagation()}>
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
                <td className="px-4 py-3"><div data-testid={`long-running-status-${instance.id}`}><StatusBadge status={instance.cockpitStatus} /></div></td>
                <td className="px-4 py-3"><span className="px-2 py-1 rounded text-xs font-mono bg-amber-500/20 text-amber-400">{formatElapsedDuration(instance.inactiveDuration)}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
      </>
    )}
  </div>
);