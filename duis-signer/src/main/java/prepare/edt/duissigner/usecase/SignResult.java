package prepare.edt.duissigner.usecase;

import prepare.edt.duissigner.entity.UserRole;

/** What the use case returns: the signed XML, or the reason it was refused. */
public sealed interface SignResult {

    record Signed(String requestId, UserRole role, String signedXml) implements SignResult {
    }

    record Refused(String requestId, String reason) implements SignResult {
    }
}
