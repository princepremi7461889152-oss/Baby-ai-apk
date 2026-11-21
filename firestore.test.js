const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const validUserDoc = (email = "alice@example.com") => ({
  email,
  plan: "free",
  tasksRemaining: 15,
  createdAt: new Date(Date.now() - 5000),
  memories: [],
  taskHistory: [],
  conversationHistory: [],
  messageHistory: [],
});

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users are denied read and write on users and settings", async () => {
  const anonDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(anonDb.collection("users").doc(ALICE_UID).get());
  await assertFails(anonDb.collection("users").doc(ALICE_UID).set(validUserDoc()));
  await assertFails(anonDb.collection("settings").doc("freemium").get());
  await assertFails(anonDb.collection("users").get());
});

test("Authenticated owner can create and read their own user document", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com" }).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validUserDoc()));
  const snap = await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
  assert.equal(snap.data().plan, "free");
  assert.equal(snap.data().tasksRemaining, 15);
});

test("Cross-user read, update, and top-level users list queries are rejected", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set(validUserDoc());
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@example.com" }).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ plan: "pro" }));
  await assertFails(bobDb.collection("users").get());
});

test("Owner can update memories, taskHistory, tasksRemaining, and plan while createdAt remains immutable", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set(validUserDoc());
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com" }).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      memories: [
        {
          id: "mem_1",
          text: "Prefers dark theme",
          source: "User",
          createdAt: new Date(Date.now() - 2000),
        },
      ],
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      taskHistory: [
        {
          task: "Open YouTube",
          status: "started",
          startedAt: new Date(Date.now() - 2000),
          completedAt: null,
          success: null,
          errorMessage: null,
        },
      ],
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({ tasksRemaining: 14 }));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({ plan: "pro" }));

  // Reject invalid plan, future createdAt, unknown fields, and deletion
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ plan: "enterprise" }));
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({ createdAt: new Date(Date.now() - 1000) })
  );
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ hacked: true }));
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).delete());
});

test("Future timestamp on user creation is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      ...validUserDoc(),
      createdAt: new Date(Date.now() + 60000),
    })
  );
});

test("Owner can create and read gemini_logs under their own user subtree only", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const logPayload = {
    userId: ALICE_UID,
    timestamp: new Date(Date.now() - 1000),
    status: "pass",
    attempt: 1,
    model: "gemini-3.8-flash",
    prompt: "Open camera",
    imagesCount: 0,
    responseTimeMs: 320,
    llmReply: "{\"Type\":\"Task\"}",
    error: null,
  };

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("gemini_logs").doc("log_1").set(logPayload)
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("gemini_logs").doc("log_1").get()
  );

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("gemini_logs").doc("log_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("gemini_logs").doc("log_2").set({
      ...logPayload,
      userId: BOB_UID,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("gemini_logs").doc("log_1").delete()
  );
});

test("Settings document is readable by authenticated users and non-writable by clients", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("settings").doc("freemium").set({
      developerMessage: "Welcome to Baby!",
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const snap = await assertSucceeds(aliceDb.collection("settings").doc("freemium").get());
  assert.equal(snap.data().developerMessage, "Welcome to Baby!");
  await assertFails(
    aliceDb.collection("settings").doc("freemium").set({ developerMessage: "Tampered" })
  );
});
