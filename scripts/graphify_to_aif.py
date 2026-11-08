#!/usr/bin/env python3
"""
Converts Graphify knowledge graph outputs (graph.json, GRAPH_REPORT.md)
into KryptonIDE / Oorty Lite AIF (.ai binary + .aif.md ledger) format.
"""

import os
import sys
import json
import struct
import time
import uuid

def write_utf(buf, s):
    data = s.encode('utf-8')
    buf.extend(struct.pack('>H', len(data)))
    buf.extend(data)

def write_int(buf, val):
    buf.extend(struct.pack('>i', val))

def write_long(buf, val):
    buf.extend(struct.pack('>q', val))

def write_float(buf, val):
    buf.extend(struct.pack('>f', val))

def write_bool(buf, val):
    buf.extend(struct.pack('>?', val))

def generate_aif(graph_json_path, report_md_path, out_ai_path, out_md_path, project_id="graphify"):
    with open(graph_json_path, 'r', encoding='utf-8') as f:
        graph = json.load(f)

    report_text = ""
    if os.path.exists(report_md_path):
        with open(report_md_path, 'r', encoding='utf-8') as f:
            report_text = f.read()

    nodes = graph.get('nodes', [])
    links = graph.get('links', []) or graph.get('edges', [])
    
    print(f"Loaded graph: {len(nodes)} nodes, {len(links)} links")

    # Group nodes by community
    communities = {}
    for n in nodes:
        c_name = n.get('community_name') or f"Community_{n.get('community', 0)}"
        communities.setdefault(c_name, []).append(n)

    # Compute node degrees (importance)
    node_degree = {}
    for l in links:
        s = l.get('source')
        t = l.get('target')
        node_degree[s] = node_degree.get(s, 0) + 1
        node_degree[t] = node_degree.get(t, 0) + 1

    top_nodes = sorted(nodes, key=lambda n: node_degree.get(n['id'], 0), reverse=True)

    blocks = []
    now = int(time.time() * 1000)

    # 1. Core Architecture Overview Block (Red Core)
    core_summary = (
        f"Graphify Knowledge Graph: {len(nodes)} nodes, {len(links)} edges across {len(communities)} communities. "
        f"Primary subsystems: AST parsing (tree-sitter), multi-platform skills (Claude, Codex, Antigravity, Oorty Lite), "
        f"query/path traversal engine, and community clustering."
    )
    blocks.append({
        'id': str(uuid.uuid4()),
        'timestamp': now,
        'projectId': project_id,
        'importance': 1.0,
        'isRedCore': True,
        'tags': ['architecture', 'core', 'graphify', 'knowledge-graph'],
        'summary': core_summary,
        'pointers': ['graphify/__main__.py', 'graphify/detect.py', 'graphify/extract.py', 'graphify/export.py']
    })

    # 2. Add Top Community / Subsystem Blocks
    top_communities = sorted(communities.items(), key=lambda kv: len(kv[1]), reverse=True)[:15]
    for c_name, c_nodes in top_communities:
        rep_files = list(set([n.get('source_file') for n in c_nodes if n.get('source_file')]))[:5]
        labels = [n.get('label') for n in c_nodes[:6]]
        summary = (
            f"Subsystem [{c_name}]: {len(c_nodes)} symbols/entities. "
            f"Key components: {', '.join(labels)}. "
            f"Source roots: {', '.join(rep_files)}."
        )
        blocks.append({
            'id': str(uuid.uuid4()),
            'timestamp': now,
            'projectId': project_id,
            'importance': 0.85,
            'isRedCore': False,
            'tags': ['subsystem', 'community', c_name.lower().replace(' ', '-')],
            'summary': summary,
            'pointers': rep_files
        })

    # 3. Add Top God Nodes (Architectural Nexus Points)
    for n in top_nodes[:20]:
        nid = n['id']
        deg = node_degree.get(nid, 0)
        label = n.get('label', nid)
        src = n.get('source_file', '')
        loc = n.get('source_location', '')
        c_name = n.get('community_name', '')
        summary = f"God Node '{label}' ({deg} connections, {c_name}): Defined in {src} {loc}. Central dependency anchor."
        blocks.append({
            'id': str(uuid.uuid4()),
            'timestamp': now,
            'projectId': project_id,
            'importance': min(0.95, 0.5 + deg / 100.0),
            'isRedCore': deg > 50,
            'tags': ['god-node', 'dependency', label.lower()],
            'summary': summary,
            'pointers': [f"{src}:{loc}" if loc else src]
        })

    # 4. Serialize to .ai Binary (AIF\x01 specification)
    os.makedirs(os.path.dirname(os.path.abspath(out_ai_path)), exist_ok=True)
    buf = bytearray()
    buf.extend(b'AIF\x01')  # Magic bytes
    write_int(buf, 1)      # Format version = 1
    write_int(buf, len(blocks)) # Block count

    for b in blocks:
        write_utf(buf, b['id'])
        write_long(buf, b['timestamp'])
        write_utf(buf, b['projectId'])
        write_float(buf, b['importance'])
        write_bool(buf, b['isRedCore'])
        write_int(buf, len(b['tags']))
        for t in b['tags']:
            write_utf(buf, t)
        write_utf(buf, b['summary'])
        write_int(buf, len(b['pointers']))
        for p in b['pointers']:
            write_utf(buf, p)

    with open(out_ai_path, 'wb') as f:
        f.write(buf)
    print(f"Wrote AIF binary: {out_ai_path} ({len(buf)} bytes, {len(blocks)} blocks)")

    # 5. Serialize to .aif.md Markdown Ledger
    md_lines = [
        f"# Cognitive Memory Ledger (AIF) - {project_id}",
        "Generated by Oorty Lite AIF Memory Manager with Graphify Knowledge Graph\n",
        f"- **Source Corpus**: https://github.com/Graphify-Labs/graphify",
        f"- **Nodes**: {len(nodes)} · **Edges**: {len(links)} · **Communities**: {len(communities)}",
        f"- **Generated Timestamp**: {time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime())}\n",
        "## Memory Blocks\n"
    ]

    for b in blocks:
        tag_str = f" [{', '.join(b['tags'])}]" if b['tags'] else ""
        ptr_str = f" (Pointers: {', '.join(b['pointers'])})" if b['pointers'] else ""
        priority_label = "RED-CORE" if b['isRedCore'] else f"Priority {b['importance']:.2f}"
        md_lines.append(f"- **({priority_label})**{tag_str}: {b['summary']}{ptr_str}")

    with open(out_md_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(md_lines) + '\n')
    print(f"Wrote AIF markdown: {out_md_path} ({len(md_lines)} lines)")

if __name__ == "__main__":
    g_json = "/tmp/graphify-repo/graphify-out/graph.json"
    r_md = "/tmp/graphify-repo/graphify-out/GRAPH_REPORT.md"
    out_ai = "app/src/main/assets/aif/graphify.ai"
    out_md = "app/src/main/assets/aif/graphify.aif.md"
    generate_aif(g_json, r_md, out_ai, out_md)
