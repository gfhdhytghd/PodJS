export class ShellCommandError extends Error {
 constructor(readonly code:'invalid_argument'|'unavailable'|'not_ready'|'not_found'){super(code);}
}
