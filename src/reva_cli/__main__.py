#!/usr/bin/env python3
"""
ReVa CLI - Main entry point.

Provides stdio MCP transport for ReVa, enabling integration with Claude CLI.
Usage: claude mcp add ReVa -- mcp-reva [--config PATH] [--verbose]
"""

import sys
import signal
import asyncio
import argparse
from pathlib import Path
from typing import Optional

from .launcher import ReVaLauncher
from .project_manager import ProjectManager
from .stdio_bridge import ReVaStdioBridge


class ReVaCLI:
    """Main CLI application."""

    def __init__(
        self,
        launcher: ReVaLauncher,
        project_manager: ProjectManager,
        server_port: int
    ):
        """
        Initialize ReVa CLI with pre-initialized components.

        Args:
            launcher: Pre-initialized ReVa server launcher
            project_manager: Pre-initialized project manager
            server_port: Port number where ReVa server is running
        """
        self.launcher = launcher
        self.project_manager = project_manager
        self.server_port = server_port
        self.bridge = None
        self.cleanup_done = False

    def setup_signal_handlers(self):
        """Setup signal handlers for clean shutdown."""
        def signal_handler(sig, frame):
            if not self.cleanup_done:
                print(f"\nReceived signal {sig}, shutting down gracefully...", file=sys.stderr)
                self.cleanup()
            sys.exit(0)

        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)

        # Handle SIGHUP on Unix systems
        if hasattr(signal, 'SIGHUP'):
            signal.signal(signal.SIGHUP, signal_handler)

    def cleanup(self):
        """Clean up all resources."""
        if self.cleanup_done:
            return

        self.cleanup_done = True
        print("Cleaning up resources...", file=sys.stderr)

        # Stop bridge
        if self.bridge:
            try:
                self.bridge.stop()
            except Exception as e:
                print(f"Error stopping bridge: {e}", file=sys.stderr)

        # Clean up project
        if self.project_manager:
            try:
                self.project_manager.cleanup()
            except Exception as e:
                print(f"Error cleaning up project: {e}", file=sys.stderr)

        # Stop server
        if self.launcher:
            try:
                self.launcher.stop()
            except Exception as e:
                print(f"Error stopping launcher: {e}", file=sys.stderr)

        print("Cleanup complete", file=sys.stderr)

    async def run(self):
        """Run the async stdio bridge (all initialization already done)."""
        try:
            # Setup signal handlers
            self.setup_signal_handlers()

            # Start stdio bridge
            print(f"Starting stdio bridge on port {self.server_port}...", file=sys.stderr)
            self.bridge = ReVaStdioBridge(self.server_port)

            # Run the bridge (this blocks until stopped)
            await self.bridge.run()

        except KeyboardInterrupt:
            print("\nInterrupted by user", file=sys.stderr)
        except Exception as e:
            print(f"Fatal error: {e}", file=sys.stderr)
            import traceback
            traceback.print_exc(file=sys.stderr)
            sys.exit(1)
        finally:
            self.cleanup()


def _check_server_running(port: int) -> bool:
    """Check if a ReVa MCP server is already listening on the given port."""
    import socket
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2):
            return True
    except (ConnectionRefusedError, OSError, TimeoutError):
        return False


def main():
    """Main entry point for mcp-reva command."""
    parser = argparse.ArgumentParser(
        description="ReVa MCP server with stdio transport for Claude CLI integration"
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="Path to ReVa configuration file"
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable verbose logging"
    )
    parser.add_argument(
        "--project",
        type=Path,
        help="Path to an existing Ghidra project directory (e.g., /path/to/MyProject.rep's parent). "
             "Opens the project persistently instead of creating an ephemeral one."
    )
    parser.add_argument(
        "--project-name",
        type=str,
        help="Ghidra project name (required with --project). "
             "This is the name used when the project was created in Ghidra."
    )
    parser.add_argument(
        "--serve",
        action="store_true",
        help="Run as a persistent HTTP server (no stdio bridge). "
             "Multiple Claude Code sessions can connect via HTTP transport. "
             "Use with --port to set a fixed port (default: 8080)."
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8080,
        help="HTTP port for --serve mode (default: 8080)"
    )
    parser.add_argument(
        "--version",
        action="version",
        version="%(prog)s 3.0.0"
    )

    args = parser.parse_args()

    # Validate config file if provided
    if args.config and not args.config.exists():
        print(f"Error: Configuration file not found: {args.config}", file=sys.stderr)
        sys.exit(1)

    # Validate project arguments
    if args.project and not args.project_name:
        print("Error: --project-name is required when using --project", file=sys.stderr)
        sys.exit(1)
    if args.project_name and not args.project:
        print("Error: --project is required when using --project-name", file=sys.stderr)
        sys.exit(1)
    if args.project and not args.project.exists():
        print(f"Error: Project directory not found: {args.project}", file=sys.stderr)
        sys.exit(1)

    # =========================================================================
    # Determine server port
    # =========================================================================
    port = args.port  # default 8080

    # Check if a ReVa server is already running on the target port
    server_already_running = _check_server_running(port)

    if server_already_running and args.serve:
        print(f"ReVa server already running on port {port}", file=sys.stderr)
        sys.exit(0)

    launcher = None
    project_manager = None

    if not server_already_running:
        # =================================================================
        # Start the server (either foreground or background)
        # =================================================================
        try:
            print("Initializing PyGhidra...", file=sys.stderr)
            import pyghidra
            pyghidra.start(verbose=args.verbose)
            print("PyGhidra initialized", file=sys.stderr)

            project_manager = ProjectManager()

            print("Starting ReVa server...", file=sys.stderr)
            launcher = ReVaLauncher(
                config_file=args.config,
                use_random_port=False,
                project_path=args.project,
                project_name=args.project_name,
                fixed_port=port,
            )
            port = launcher.start()
            print(f"ReVa server ready on port {port}", file=sys.stderr)

        except Exception as e:
            print(f"Initialization error: {e}", file=sys.stderr)
            import traceback
            traceback.print_exc(file=sys.stderr)
            sys.exit(1)
    else:
        print(f"ReVa server already running on port {port}, connecting...", file=sys.stderr)

    if args.serve:
        # =====================================================================
        # HTTP SERVER MODE — block until Ctrl+C
        # =====================================================================
        print(f"ReVa HTTP server running on http://localhost:{port}/mcp/message", file=sys.stderr)
        print("Press Ctrl+C to stop.", file=sys.stderr)

        def shutdown(sig, frame):
            print("\nShutting down...", file=sys.stderr)
            if launcher:
                launcher.stop()
            if project_manager:
                project_manager.cleanup()
            sys.exit(0)

        signal.signal(signal.SIGINT, shutdown)
        signal.signal(signal.SIGTERM, shutdown)
        if hasattr(signal, 'SIGHUP'):
            signal.signal(signal.SIGHUP, shutdown)

        try:
            signal.pause() if hasattr(signal, 'pause') else asyncio.get_event_loop().run_forever()
        except KeyboardInterrupt:
            shutdown(None, None)
    else:
        # =====================================================================
        # STDIO BRIDGE MODE (default) — bridge stdio to the HTTP server
        # =====================================================================
        # If we started the server, we own it. If we connected to an existing
        # one, launcher is None and cleanup is a no-op.
        cli = ReVaCLI(
            launcher=launcher,
            project_manager=project_manager,
            server_port=port
        )

        try:
            asyncio.run(cli.run())
        except KeyboardInterrupt:
            print("\nShutdown complete", file=sys.stderr)
            sys.exit(0)


if __name__ == "__main__":
    main()
