# Bob Hackathon Tasker

## Planner and scheduler

The reusable Agent DevKit planner is included as `planner-core`, `planner`, `agent-acp`, and `planner-app`. IBM Bob built the independent `scheduler` module and its CLI integration. New issues require `target_files` in frontmatter; the scheduler uses those paths to avoid choosing issues that target the same files. `schedule --issues` proposes planning batches without ACP review; it does not execute tasks or certify an implementation-ready plan.

Requires a JDK 25 toolchain (emits Java 21 bytecode). Run all foundation checks with:

```bash
./gradlew check
./gradlew :planner-app:installDist
cd examples/name-validation
PLANNER=../../planner-app/build/install/planner-app/bin/planner-app
$PLANNER issue new --title "Reject blank names" --target src/main/kotlin/sample/Main.kt
# Replace <printed-file> with the name printed by issue new:
$PLANNER schedule --issues docs/internals/backlog/<printed-file>.md --capacity 2
```

Fill in Context in the printed issue when ready. The required `target_files` are already recorded; add more paths there if needed. See [PLANNER.md](PLANNER.md) for elaboration and review commands, [ARCHITECTURE.md](ARCHITECTURE.md) for module boundaries, and [examples/name-validation](examples/name-validation) for a small sample project. An ACP profile example is in [examples/planner-profile.example.json](examples/planner-profile.example.json); substitute your local executable and authenticate it separately.

`EXPORT-MANIFEST.txt` records the source revision and hashes of the copied foundation, plus destination-specific changes. This is pre-existing foundation code; record new Bob work and its task-session screenshots in `bob_sessions/`. No credentials or machine-specific agent configuration are included.

## Original IBM Hackathon template guidance

This GitHub project template is for IBM Hackathon projects. It includes pre-configured security files to help prevent accidental credential commits and potential account suspension during the hackathon.

## 🚀 Quick Start

1. **Use this template to create your project:**
   - Click "Use this template" button above and select "Create a new repository"
   - Name your repository
   - Click "Create repository"

2. **Clone your new repository:**

   ```bash
   git clone https://github.com/HACKATHON-ORG/your-repo-name.git
   cd your-repo-name
   ```

3. **Set up environment variables:**

   ```bash
   # Copy the example file
   cp .env.example .env

   # Edit .env with your actual credentials
   # Use your preferred editor (nano, vim, code, etc.)
   nano .env
   ```

4. **Verify .gitignore is working:**

   ```bash
   # This should NOT show .env file
   git status

   # This should confirm .env is ignored
   git check-ignore -v .env
   ```

5. **Start developing!**

## 🔒 Security Features

This template includes:

- **`.gitignore`** - Prevents committing credentials and live session files
- **`.bobignore`** - Prevents AI assistants from logging credentials
- **`.env.example`** - Template for your environment variables

## 📋 Before Every Commit

Always run this checklist:

- [ ] Reviewed `git diff` for sensitive data
- [ ] No hardcoded API keys or passwords
- [ ] `.env` file is NOT in staged changes
- [ ] No files with "credential" or "secret" in name
- [ ] Used environment variables for all credentials

## 🆘 Need Help?

- Read [SECURITY.md](SECURITY.MD) for detailed guidelines
- Contact hackathon support through mentor channel
- Ask in the hackathon Slack workspace

---

**Remember:** Security is everyone's responsibility. When in doubt, ask for help!
