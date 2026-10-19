const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

/**
 * Callable Function: joinGroup
 * Securely redeems invite code and adds the authenticated user to groups/main/members.
 */
exports.joinGroup = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required to join group.");
  }

  const uid = request.auth.uid;
  const inviteCode = (request.data.inviteCode || "").trim();

  if (!inviteCode) {
    throw new HttpsError("invalid-argument", "Invite code is required.");
  }

  const groupRef = db.collection("groups").doc("main");
  const groupDoc = await groupRef.get();

  let expectedInviteCode = "SNITCH2026";
  if (groupDoc.exists && groupDoc.data().inviteCode) {
    expectedInviteCode = groupDoc.data().inviteCode;
  } else if (!groupDoc.exists) {
    // Seed groups/main metadata document if missing
    await groupRef.set({
      groupId: "main",
      name: "Snitch Shared Group",
      inviteCode: "SNITCH2026",
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
  }

  if (inviteCode.toLowerCase() !== expectedInviteCode.toLowerCase()) {
    throw new HttpsError("permission-denied", "Invalid group invite code.");
  }

  const memberRef = groupRef.collection("members").doc(uid);
  const memberDoc = await memberRef.get();

  if (memberDoc.exists) {
    throw new HttpsError("already-exists", "You are already a member of this group.");
  }

  const userDoc = await db.collection("users").doc(uid).get();
  const userData = userDoc.data() || {};
  const displayName = userData.displayName || request.auth.token.name || request.auth.token.email || `User_${uid.substring(0, 5)}`;

  const batch = db.batch();
  batch.set(memberRef, {
    uid: uid,
    displayName: displayName,
    email: userData.email || request.auth.token.email || "",
    role: "member",
    joinedAt: admin.firestore.FieldValue.serverTimestamp()
  });

  batch.update(db.collection("users").doc(uid), {
    groupId: "main"
  });

  await batch.commit();
  return { success: true, message: "Joined group main successfully." };
});

/**
 * Callable Function: voteOnRequest
 * Allows group member to cast vote and triggers voting evaluation.
 */
exports.voteOnRequest = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication required.");
  }

  const uid = request.auth.uid;
  const { requestId, approve } = request.data;

  if (!requestId || typeof approve !== "boolean") {
    throw new HttpsError("invalid-argument", "requestId and approve boolean are required.");
  }

  const requestRef = db.collection("groups").doc("main").collection("timeRequests").doc(requestId);
  const requestDoc = await requestRef.get();

  if (!requestDoc.exists) {
    throw new HttpsError("not-found", "Time request not found.");
  }

  const reqData = requestDoc.data();
  if (reqData.requesterUid === uid) {
    throw new HttpsError("permission-denied", "Requesters cannot vote on their own request.");
  }

  if (reqData.status !== "pending") {
    throw new HttpsError("failed-precondition", `Request is already ${reqData.status}.`);
  }

  const userDoc = await db.collection("users").doc(uid).get();
  const voterName = (userDoc.data() && userDoc.data().displayName) || request.auth.token.name || "Group Member";

  const voteRef = requestRef.collection("votes").doc(uid);
  await voteRef.set({
    uid: uid,
    voterName: voterName,
    approved: approve,
    votedAt: admin.firestore.FieldValue.serverTimestamp()
  });

  // Evaluate voting results
  await evaluateVotingThreshold(requestId);
  return { success: true };
});

/**
 * Firestore Trigger: automatically evaluates voting threshold when a vote document is created
 */
exports.onVoteWritten = onDocumentCreated(
  "groups/main/timeRequests/{requestId}/votes/{voterUid}",
  async (event) => {
    const requestId = event.params.requestId;
    await evaluateVotingThreshold(requestId);
  }
);

/**
 * Helper function to count votes and issue grants securely
 */
async function evaluateVotingThreshold(requestId) {
  const requestRef = db.collection("groups").doc("main").collection("timeRequests").doc(requestId);

  await db.runTransaction(async (transaction) => {
    const requestDoc = await transaction.get(requestRef);
    if (!requestDoc.exists) return;

    const reqData = requestDoc.data();
    if (reqData.status !== "pending") return;

    const votesSnapshot = await transaction.get(requestRef.collection("votes"));
    let approveCount = 0;
    let rejectCount = 0;

    votesSnapshot.forEach((doc) => {
      if (doc.data().approved) {
        approveCount++;
      } else {
        rejectCount++;
      }
    });

    const membersSnapshot = await transaction.get(db.collection("groups").doc("main").collection("members"));
    const totalMembers = membersSnapshot.size;
    const eligibleVoters = Math.max(1, totalMembers - 1); // Exclude requester

    transaction.update(requestRef, {
      approveVotesCount: approveCount,
      rejectVotesCount: rejectCount
    });

    // Approval condition: at least 1 approve vote (or majority of eligible voters)
    if (approveCount >= 1 && approveCount >= Math.ceil(eligibleVoters / 2)) {
      transaction.update(requestRef, { status: "approved" });

      const grantRef = db.collection("groups").doc("main").collection("grants").doc();
      transaction.set(grantRef, {
        grantId: grantRef.id,
        requestId: requestId,
        recipientUid: reqData.requesterUid,
        packageName: reqData.packageName,
        minutes: reqData.requestedMinutes,
        issuedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } else if (rejectCount >= Math.ceil(eligibleVoters / 2)) {
      transaction.update(requestRef, { status: "rejected" });
    }
  });
}
