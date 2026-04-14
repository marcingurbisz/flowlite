import { AlertCircle, CheckCircle, Clock, Play, X } from 'lucide-react';
import type { CockpitStatus } from './types';

const statusConfig: Record<CockpitStatus, { bg: string; text: string; label: string }> = {
  Running: { bg: 'bg-blue-500/20', text: 'text-blue-400', label: 'Running' },
  WaitingForTimer: { bg: 'bg-violet-500/20', text: 'text-violet-300', label: 'Waiting for timer' },
  WaitingForEvent: { bg: 'bg-zinc-700', text: 'text-zinc-300', label: 'Waiting for event' },
  PendingEngine: { bg: 'bg-amber-500/20', text: 'text-amber-400', label: 'Pending engine' },
  Completed: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', label: 'Completed' },
  Error: { bg: 'bg-red-500/20', text: 'text-red-400', label: 'Error' },
  Cancelled: { bg: 'bg-zinc-500/20', text: 'text-zinc-400', label: 'Cancelled' },
};

export const StatusBadge = ({ status }: { status: CockpitStatus }) => {
  const style = statusConfig[status];

  return (
    <span className={`px-2 py-1 rounded text-xs font-mono ${style.bg} ${style.text} flex items-center gap-1`}>
      {status === 'Error' && <AlertCircle size={12} />}
      {status === 'Completed' && <CheckCircle size={12} />}
      {status === 'Cancelled' && <X size={12} />}
      {status === 'PendingEngine' && <Clock size={12} />}
      {status === 'Running' && <Play size={12} />}
      {style.label}
    </span>
  );
};