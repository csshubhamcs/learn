package com.learn.taskservice.common.exception;

/**
 * Thrown when the resource exists but belongs to somebody other than the caller. Maps to
 * HTTP 403.
 *
 * <h2>Why 403 here and 404 for a nonexistent id</h2>
 *
 * A 403 on "exists, but not yours" does leak one bit: that the id exists. That leak is
 * accepted deliberately, for three reasons.
 *
 * <ol>
 *   <li>Task ids are random UUIDv4s, never sequential and never exposed in any listing but
 *       the owner's own. Guessing one to harvest the leaked bit is not a practical attack,
 *       and a task id carries no meaning by itself - unlike, say, a username or an invoice
 *       number, where mere existence is the secret.
 *   <li>The two answers are genuinely different problems for the caller, and collapsing them
 *       into one status makes every "my client has a stale id" bug indistinguishable from
 *       "my client is authenticated as the wrong user". That ambiguity costs real debugging
 *       time on every integration, forever.
 *   <li>It makes the guarantee testable end to end. A test that asserts 403 proves the
 *       ownership check ran and rejected the caller. A test that asserts 404 cannot tell an
 *       enforced ownership check apart from a query that silently filtered the row out - the
 *       exact failure mode this service is built to prevent - so the safer-looking status
 *       would leave the central requirement unverifiable.
 * </ol>
 *
 * The rule is therefore: <strong>404 when no task with that id exists, 403 when it exists
 * and the caller is not its owner</strong>, applied uniformly to GET, PUT and DELETE. If a
 * future resource in this service ever holds data where existence itself is sensitive, that
 * resource should return 404 for both and say so in its own Javadoc - but it must not change
 * this one silently, because the tests pin the contract.
 */
public class NotResourceOwnerException extends BaseException {
    public NotResourceOwnerException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
