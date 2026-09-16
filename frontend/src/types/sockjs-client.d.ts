declare module 'sockjs-client' {
  interface SockJSOptions {
    server?: string;
    sessionId?: number | (() => number);
    transports?: string | string[];
    timeout?: number;
    protocolVersions?: string[];
  }

  type State = 'CONNECTING' | 'OPEN' | 'CLOSING' | 'CLOSED';

  class SockJS {
    static CONNECTING: State;
    static OPEN: State;
    static CLOSING: State;
    static CLOSED: State;

    constructor(url: string, _reserved?: unknown, options?: SockJSOptions);

    readonly readyState: State;
    readonly protocol: string;
    readonly url: string;

    onopen: (() => void) | null;
    onclose: ((event: unknown) => void) | null;
    onmessage: ((event: { data: string }) => void) | null;

    send(data: string | ArrayBuffer): void;
    close(code?: number, reason?: string): void;
  }

  export default SockJS;
}