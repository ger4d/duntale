#!/usr/bin/env python3
"""
Task management CLI for v3-zsquad development workflow.

Usage:
    python cli.py create   --name <slug> --desc <description> [--priority <low|medium|high|critical>]
    python cli.py state    --name <slug> --state <created|planned|in-progress|completed>
    python cli.py get      --name <slug>
    python cli.py latest   [--state <state>]
    python cli.py plan-add --name <slug> (--text <plan> | --file <path>)
    python cli.py plan-get --name <slug>
    python cli.py list     [--state <state>]
"""

import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path

DB_PATH = Path(__file__).parent / "tasks.db"
SCHEMA_PATH = Path(__file__).parent / "schema.sql"

VALID_STATES = ("created", "planned", "in-progress", "completed")
STATE_ORDER = {s: i for i, s in enumerate(VALID_STATES)}


def get_connection():
    db_exists = DB_PATH.exists()
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    if not db_exists:
        with open(SCHEMA_PATH) as f:
            conn.executescript(f.read())
    return conn


def _row_to_dict(row):
    if row is None:
        return None
    return dict(row)


def _print_json(data):
    print(json.dumps(data, indent=2, default=str))


# ── Commands ──────────────────────────────────────────────────────────────────

def cmd_create(args):
    conn = get_connection()
    try:
        conn.execute(
            "INSERT INTO tasks (name, description, priority) VALUES (?, ?, ?)",
            (args.name, args.desc, args.priority),
        )
        conn.commit()
        task = _row_to_dict(conn.execute("SELECT * FROM tasks WHERE name = ?", (args.name,)).fetchone())
        _print_json(task)
    except sqlite3.IntegrityError:
        print(f"Error: task '{args.name}' already exists.", file=sys.stderr)
        sys.exit(1)
    finally:
        conn.close()


def cmd_state(args):
    conn = get_connection()
    try:
        row = conn.execute("SELECT * FROM tasks WHERE name = ?", (args.name,)).fetchone()
        if row is None:
            print(f"Error: task '{args.name}' not found.", file=sys.stderr)
            sys.exit(1)

        current = row["state"]
        target = args.state

        # Enforce strict state transitions: created → planned → in-progress → completed
        if STATE_ORDER.get(target, -1) != STATE_ORDER.get(current, -1) + 1:
            print(
                f"Error: invalid transition '{current}' → '{target}'. "
                f"Expected next state: '{VALID_STATES[STATE_ORDER[current] + 1] if STATE_ORDER[current] < len(VALID_STATES) - 1 else 'none (already completed)'}'"
                , file=sys.stderr,
            )
            sys.exit(1)

        conn.execute(
            "UPDATE tasks SET state = ?, updated_at = datetime('now') WHERE name = ?",
            (target, args.name),
        )
        conn.commit()
        task = _row_to_dict(conn.execute("SELECT * FROM tasks WHERE name = ?", (args.name,)).fetchone())
        _print_json(task)
    finally:
        conn.close()


def cmd_get(args):
    conn = get_connection()
    try:
        row = conn.execute("SELECT * FROM tasks WHERE name = ?", (args.name,)).fetchone()
        if row is None:
            print(f"Error: task '{args.name}' not found.", file=sys.stderr)
            sys.exit(1)
        _print_json(_row_to_dict(row))
    finally:
        conn.close()


def cmd_latest(args):
    conn = get_connection()
    try:
        if args.state:
            row = conn.execute(
                "SELECT * FROM tasks WHERE state = ? ORDER BY updated_at DESC LIMIT 1",
                (args.state,),
            ).fetchone()
        else:
            row = conn.execute(
                "SELECT * FROM tasks ORDER BY updated_at DESC LIMIT 1"
            ).fetchone()
        if row is None:
            print("No tasks found.", file=sys.stderr)
            sys.exit(1)
        _print_json(_row_to_dict(row))
    finally:
        conn.close()


def cmd_plan_add(args):
    conn = get_connection()
    try:
        row = conn.execute("SELECT id FROM tasks WHERE name = ?", (args.name,)).fetchone()
        if row is None:
            print(f"Error: task '{args.name}' not found.", file=sys.stderr)
            sys.exit(1)
        task_id = row["id"]

        if args.file:
            plan_path = Path(args.file)
            if not plan_path.exists():
                print(f"Error: file '{args.file}' not found.", file=sys.stderr)
                sys.exit(1)
            plan_text = plan_path.read_text(encoding="utf-8")
        else:
            plan_text = args.text

        # Get next version
        ver_row = conn.execute(
            "SELECT COALESCE(MAX(version), 0) + 1 AS next_ver FROM implementation_plans WHERE task_id = ?",
            (task_id,),
        ).fetchone()
        next_ver = ver_row["next_ver"]

        conn.execute(
            "INSERT INTO implementation_plans (task_id, plan, version) VALUES (?, ?, ?)",
            (task_id, plan_text, next_ver),
        )
        conn.commit()
        print(json.dumps({"task": args.name, "version": next_ver, "status": "inserted"}))
    finally:
        conn.close()


def cmd_plan_get(args):
    conn = get_connection()
    try:
        row = conn.execute("SELECT id FROM tasks WHERE name = ?", (args.name,)).fetchone()
        if row is None:
            print(f"Error: task '{args.name}' not found.", file=sys.stderr)
            sys.exit(1)
        task_id = row["id"]

        plan_row = conn.execute(
            "SELECT * FROM implementation_plans WHERE task_id = ? ORDER BY version DESC LIMIT 1",
            (task_id,),
        ).fetchone()
        if plan_row is None:
            print(f"Error: no plan found for task '{args.name}'.", file=sys.stderr)
            sys.exit(1)
        _print_json(_row_to_dict(plan_row))
    finally:
        conn.close()


def cmd_list(args):
    conn = get_connection()
    try:
        if args.state:
            rows = conn.execute(
                "SELECT * FROM tasks WHERE state = ? ORDER BY updated_at DESC", (args.state,)
            ).fetchall()
        else:
            rows = conn.execute("SELECT * FROM tasks ORDER BY updated_at DESC").fetchall()
        _print_json([_row_to_dict(r) for r in rows])
    finally:
        conn.close()


# ── Argument Parser ───────────────────────────────────────────────────────────

def build_parser():
    parser = argparse.ArgumentParser(description="v3-zsquad task management CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    # create
    p_create = sub.add_parser("create", help="Create a new task")
    p_create.add_argument("--name", required=True, help="Task slug (unique)")
    p_create.add_argument("--desc", required=True, help="Task description")
    p_create.add_argument("--priority", default="medium", choices=["low", "medium", "high", "critical"])

    # state
    p_state = sub.add_parser("state", help="Update task state")
    p_state.add_argument("--name", required=True)
    p_state.add_argument("--state", required=True, choices=list(VALID_STATES))

    # get
    p_get = sub.add_parser("get", help="Get task by name")
    p_get.add_argument("--name", required=True)

    # latest
    p_latest = sub.add_parser("latest", help="Get latest task")
    p_latest.add_argument("--state", choices=list(VALID_STATES))

    # plan-add
    p_plan_add = sub.add_parser("plan-add", help="Add/update implementation plan")
    p_plan_add.add_argument("--name", required=True, help="Task name")
    group = p_plan_add.add_mutually_exclusive_group(required=True)
    group.add_argument("--text", help="Plan text (inline)")
    group.add_argument("--file", help="Path to plan file")

    # plan-get
    p_plan_get = sub.add_parser("plan-get", help="Get implementation plan")
    p_plan_get.add_argument("--name", required=True)

    # list
    p_list = sub.add_parser("list", help="List tasks")
    p_list.add_argument("--state", choices=list(VALID_STATES))

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()

    dispatch = {
        "create": cmd_create,
        "state": cmd_state,
        "get": cmd_get,
        "latest": cmd_latest,
        "plan-add": cmd_plan_add,
        "plan-get": cmd_plan_get,
        "list": cmd_list,
    }

    dispatch[args.command](args)


if __name__ == "__main__":
    main()
