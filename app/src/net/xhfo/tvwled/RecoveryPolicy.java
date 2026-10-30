package net.xhfo.tvwled;

/** Intent and callback leases shared by the optional system-bound recovery host. */
final class RecoveryPolicy {
    private RecoveryPolicy(){}
    static boolean mayRecover(boolean serverEnabled,boolean autoStart){return serverEnabled&&autoStart;}
    static boolean mayHost(boolean serverEnabled,boolean autoStart,boolean manualRequested,boolean establishedHost){
        // Auto-start controls a future process. An already active backend keeps this session.
        // Merely binding a newly-created service is not an established session.
        return serverEnabled&&(autoStart||manualRequested||establishedHost);
    }
    /** A finite retry window; releasing a host invalidates every queued callback from that window. */
    static final class BindWindow {
        private long generation;
        private int attempts;
        private boolean active;
        void begin(){generation++;attempts=0;active=true;}
        void end(){generation++;attempts=0;active=false;}
        long next(boolean allowed){
            if(!active||!allowed||attempts>=3)return -1;
            attempts++;return generation;
        }
        boolean accepts(long lease,boolean allowed){return active&&allowed&&lease>=0&&lease==generation;}
    }
}
