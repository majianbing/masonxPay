'use client';

import { useAuthStore } from '@/store/auth';

export default function ModeToggle() {
  const mode = useAuthStore((s) => s.mode);
  const toggleMode = useAuthStore((s) => s.toggleMode);

  return (
    <button
      onClick={toggleMode}
      className="flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors hover:bg-gray-50"
      title="Toggle test/live mode"
    >
      <span className={`size-2 rounded-full ${mode === 'TEST' ? 'bg-yellow-400' : 'bg-green-500'}`} />
      {mode === 'TEST' ? 'Test Mode' : 'Live Mode'}
    </button>
  );
}
