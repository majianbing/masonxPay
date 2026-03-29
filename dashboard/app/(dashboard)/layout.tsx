import Sidebar from '@/components/layout/Sidebar';
import TopBar from '@/components/layout/TopBar';
import MfaWarningBanner from '@/components/mfa-warning-banner';

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      <Sidebar />
      <div className="flex flex-col flex-1 overflow-hidden">
        <TopBar />
        <MfaWarningBanner />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
    </div>
  );
}
