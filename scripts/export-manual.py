#!/usr/bin/env python3
"""Generate Ridley manual as Markdown with screenshots.

Connects to the Ridley nREPL/shadow-cljs REPL, evaluates each manual
example, captures a screenshot, and assembles Manual_en.md / Manuale_it.md.

Usage:
    python scripts/export-manual.py [--lang en|it|both] [--no-images] [--port 7888]

Prerequisites:
    - npm run dev (shadow-cljs watch running)
    - Browser open at http://localhost:9000
    - pip install nrepl-python (or use subprocess with clj-nrepl-eval)
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
PROJECT_DIR = SCRIPT_DIR.parent
OUTPUT_DIR = PROJECT_DIR / "manual-output"
CLJ_EVAL = os.path.expanduser("~/.local/bin/clj-nrepl-eval")


def nrepl_eval(code, port=7888, timeout=30):
    """Evaluate code via clj-nrepl-eval and return the output string."""
    try:
        result = subprocess.run(
            [CLJ_EVAL, "-p", str(port), "-t", str(timeout * 1000), code],
            capture_output=True, text=True, timeout=timeout + 5
        )
        # Extract the result line (after the => marker)
        output = result.stdout
        for line in output.strip().split("\n"):
            if line.startswith("=> "):
                return line[3:]
        return output.strip()
    except subprocess.TimeoutExpired:
        print(f"  TIMEOUT: {code[:60]}...")
        return None
    except Exception as e:
        print(f"  ERROR: {e}")
        return None


def ensure_cljs_mode(port):
    """Make sure the nREPL session is in CLJS mode."""
    result = nrepl_eval("(+ 1 1)", port)
    if result and "No available JS runtime" in str(result):
        print("ERROR: No JS runtime. Open http://localhost:9000 first.")
        sys.exit(1)
    # Check if we're in CLJS mode already
    nrepl_eval("(shadow/repl :app)", port)
    # Verify
    result = nrepl_eval("(+ 1 1)", port)
    if result != "2":
        print(f"WARNING: Unexpected REPL result: {result}")


def get_manual_structure(port):
    """Fetch the manual structure and i18n data from Ridley via nREPL."""
    # Get structure as JSON
    code = """
    (let [structure ridley.manual.content/structure
          i18n ridley.manual.content/i18n
          sections (mapv (fn [section]
                           {:id (name (:id section))
                            :pages (mapv (fn [page]
                                           {:id (name (:id page))
                                            :examples (mapv (fn [ex]
                                                              {:id (name (:id ex))
                                                               :code (:code ex)})
                                                            (:examples page))})
                                         (:pages section))})
                         (:sections structure))]
      (js/JSON.stringify (clj->js {:sections sections}) nil 2))
    """
    result = nrepl_eval(code, port, timeout=15)
    if not result or result == "nil":
        print("ERROR: Could not fetch manual structure")
        sys.exit(1)
    # Remove surrounding quotes if present
    if result.startswith('"') and result.endswith('"'):
        result = json.loads(result)
    return json.loads(result)


def get_i18n_page(lang, page_id, port):
    """Fetch i18n content for a specific page."""
    code = f"""
    (let [data (get-in ridley.manual.content/i18n [:{lang} :pages :{page_id}])
          examples-map (when (:examples data)
                         (into {{}} (map (fn [[k v]]
                                          [(name k) v])
                                        (:examples data))))]
      (js/JSON.stringify
        (clj->js (-> data
                     (dissoc :examples)
                     (assoc :examples (or examples-map {{}}))))
        nil 2))
    """
    result = nrepl_eval(code, port, timeout=10)
    if not result or result == "nil":
        return {}
    if result.startswith('"') and result.endswith('"'):
        result = json.loads(result)
    try:
        return json.loads(result)
    except json.JSONDecodeError:
        return {}


def get_i18n_section(lang, section_id, port):
    """Fetch i18n content for a section header."""
    code = f'(get-in ridley.manual.content/i18n [:{lang} :sections :{section_id} :title])'
    result = nrepl_eval(code, port, timeout=10)
    if result and result.startswith('"') and result.endswith('"'):
        return result[1:-1]
    return section_id


def evaluate_and_capture(code, port, img_width=600, img_height=400):
    """Evaluate example code and capture a screenshot. Returns base64 PNG or None."""
    # Reset scene
    nrepl_eval('(js/eval "ridley.editor.repl.reset_ctx_BANG_()") (ridley.scene.registry/clear-all!)',
               port, timeout=10)
    time.sleep(0.1)

    # Evaluate the example
    eval_code = f'(ridley.editor.repl/evaluate nil {json.dumps(code)})'
    result = nrepl_eval(eval_code, port, timeout=15)
    if result is None or ":error" in str(result):
        return None

    time.sleep(0.2)

    # Refresh viewport
    nrepl_eval("(ridley.scene.registry/refresh-viewport! true)", port, timeout=5)
    time.sleep(0.1)

    # Capture screenshot
    capture_code = f"(ridley.viewport.capture/render-view :perspective :width {img_width} :height {img_height})"
    data_url = nrepl_eval(capture_code, port, timeout=15)

    if not data_url or not data_url.startswith('"data:image/png;base64,'):
        return None

    # Extract base64 data (remove quotes and prefix)
    data_url = data_url.strip('"')
    b64_data = data_url.split(",", 1)[1]
    return b64_data


def generate_manual(lang, with_images, port):
    """Generate the full manual markdown for a language."""
    print(f"\nFetching manual structure...")
    structure = get_manual_structure(port)

    filename = "Manual_en.md" if lang == "en" else "Manuale_it.md"
    title = "Ridley Manual" if lang == "en" else "Manuale Ridley"
    img_dir = OUTPUT_DIR / "images"

    if with_images:
        img_dir.mkdir(parents=True, exist_ok=True)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    lines = [f"# {title}\n\n"]

    total_examples = sum(
        len(page.get("examples", []))
        for section in structure["sections"]
        for page in section.get("pages", [])
    )
    example_num = 0

    for section in structure["sections"]:
        section_id = section["id"]
        section_title = get_i18n_section(lang, section_id, port)
        lines.append(f"## {section_title}\n\n")

        for page in section.get("pages", []):
            page_id = page["id"]
            page_i18n = get_i18n_page(lang, page_id, port)

            page_title = page_i18n.get("title", page_id)
            lines.append(f"### {page_title}\n\n")

            if page_i18n.get("content"):
                lines.append(page_i18n["content"] + "\n\n")

            examples = page.get("examples", [])
            examples_i18n = page_i18n.get("examples", {})

            for example in examples:
                example_num += 1
                ex_id = example["id"]
                code = example["code"]
                ex_i18n = examples_i18n.get(ex_id, {})
                caption = ex_i18n.get("caption", "")
                description = ex_i18n.get("description", "")

                progress = f"[{example_num}/{total_examples}]"
                print(f"  {progress} {caption or ex_id}", end="")

                if caption:
                    lines.append(f"#### {caption}\n\n")

                lines.append(f"```clojure\n{code}\n```\n\n")

                if description:
                    lines.append(f"{description}\n\n")

                if with_images:
                    b64 = evaluate_and_capture(code, port)
                    if b64:
                        img_name = f"{page_id}_{ex_id}.png"
                        img_path = img_dir / img_name
                        with open(img_path, "wb") as f:
                            f.write(base64.b64decode(b64))
                        lines.append(f"![{caption or ex_id}](images/{img_name})\n\n")
                        print(" ✓")
                    else:
                        print(" ✗ (no image)")
                else:
                    print(" ✓")

            lines.append("---\n\n")

    output_path = OUTPUT_DIR / filename
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("".join(lines))

    print(f"\n✓ Written {output_path}")
    if with_images:
        img_count = len(list(img_dir.glob("*.png")))
        print(f"  {img_count} images in {img_dir}")


def main():
    parser = argparse.ArgumentParser(description="Export Ridley manual to Markdown")
    parser.add_argument("--lang", default="both", choices=["en", "it", "both"],
                        help="Language (default: both)")
    parser.add_argument("--no-images", action="store_true",
                        help="Skip screenshot generation")
    parser.add_argument("--port", type=int, default=7888,
                        help="nREPL port (default: 7888)")
    args = parser.parse_args()

    print("Ridley Manual Export")
    print(f"  Port: {args.port}")
    print(f"  Images: {'no' if args.no_images else 'yes'}")

    # Verify nREPL connection
    print("\nConnecting to nREPL...")
    ensure_cljs_mode(args.port)
    print("Connected!")

    langs = ["en", "it"] if args.lang == "both" else [args.lang]
    for lang in langs:
        generate_manual(lang, not args.no_images, args.port)

    print("\nDone!")


if __name__ == "__main__":
    main()
