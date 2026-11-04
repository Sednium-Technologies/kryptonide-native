/**
 * Monaco Editor JavaScript Shim & Polyfill for Kryptonide.
 * Exposes the standard `window.monaco` API so VS Code / Monaco web extensions
 * can run headlessly and communicate with the native Sora Editor via `window.KryptonNative`.
 */
(function() {
    'use strict';

    if (window.monaco) return;

    // ── Console Logging Bridge ────────────────────────────────────────────────
    const origLog = console.log;
    const origWarn = console.warn;
    const origError = console.error;

    console.log = function(...args) {
        origLog.apply(console, args);
        if (window.KryptonNative && window.KryptonNative.postConsoleLog) {
            window.KryptonNative.postConsoleLog('info', args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '));
        }
    };
    console.warn = function(...args) {
        origWarn.apply(console, args);
        if (window.KryptonNative && window.KryptonNative.postConsoleLog) {
            window.KryptonNative.postConsoleLog('warn', args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '));
        }
    };
    console.error = function(...args) {
        origError.apply(console, args);
        if (window.KryptonNative && window.KryptonNative.postConsoleLog) {
            window.KryptonNative.postConsoleLog('error', args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '));
        }
    };

    // ── Monaco Namespace Definition ───────────────────────────────────────────
    const monaco = {
        editor: {},
        languages: {},
        _models: new Map(),
        _currentModel: null,
        _completionProviders: new Map(),
        _hoverProviders: new Map(),
        _formattingProviders: new Map(),
        _commands: new Map(),
        _actions: new Map()
    };

    // ── Geometry & Primitives ─────────────────────────────────────────────────
    class Position {
        constructor(lineNumber, column) {
            this.lineNumber = lineNumber;
            this.column = column;
        }
        equals(other) {
            return other && this.lineNumber === other.lineNumber && this.column === other.column;
        }
        isBefore(other) {
            if (this.lineNumber < other.lineNumber) return true;
            if (this.lineNumber === other.lineNumber) return this.column < other.column;
            return false;
        }
        isBeforeOrEqual(other) {
            if (this.lineNumber < other.lineNumber) return true;
            if (this.lineNumber === other.lineNumber) return this.column <= other.column;
            return false;
        }
    }

    class Range {
        constructor(startLineNumber, startColumn, endLineNumber, endColumn) {
            this.startLineNumber = startLineNumber;
            this.startColumn = startColumn;
            this.endLineNumber = endLineNumber;
            this.endColumn = endColumn;
        }
        isEmpty() {
            return this.startLineNumber === this.endLineNumber && this.startColumn === this.endColumn;
        }
        containsPosition(position) {
            if (position.lineNumber < this.startLineNumber || position.lineNumber > this.endLineNumber) return false;
            if (position.lineNumber === this.startLineNumber && position.column < this.startColumn) return false;
            if (position.lineNumber === this.endLineNumber && position.column > this.endColumn) return false;
            return true;
        }
        getStartPosition() {
            return new Position(this.startLineNumber, this.startColumn);
        }
        getEndPosition() {
            return new Position(this.endLineNumber, this.endColumn);
        }
    }

    class Selection extends Range {
        constructor(selectionStartLineNumber, selectionStartColumn, positionLineNumber, positionColumn) {
            super(selectionStartLineNumber, selectionStartColumn, positionLineNumber, positionColumn);
            this.selectionStartLineNumber = selectionStartLineNumber;
            this.selectionStartColumn = selectionStartColumn;
            this.positionLineNumber = positionLineNumber;
            this.positionColumn = positionColumn;
        }
    }

    monaco.Position = Position;
    monaco.Range = Range;
    monaco.Selection = Selection;

    // ── Enums ─────────────────────────────────────────────────────────────────
    monaco.MarkerSeverity = {
        Hint: 1,
        Info: 2,
        Warning: 4,
        Error: 8
    };

    monaco.KeyCode = {
        Backspace: 1, Tab: 2, Enter: 3, Shift: 4, Ctrl: 5, Alt: 6, PauseBreak: 7,
        CapsLock: 8, Escape: 9, Space: 10, PageUp: 11, PageDown: 12, End: 13, Home: 14,
        LeftArrow: 15, UpArrow: 16, RightArrow: 17, DownArrow: 18, Insert: 19, Delete: 20,
        KEY_0: 21, KEY_1: 22, KEY_2: 23, KEY_3: 24, KEY_4: 25, KEY_5: 26, KEY_6: 27, KEY_7: 28, KEY_8: 29, KEY_9: 30,
        KEY_A: 31, KEY_B: 32, KEY_C: 33, KEY_D: 34, KEY_E: 35, KEY_F: 36, KEY_G: 37, KEY_H: 38, KEY_I: 39,
        KEY_J: 40, KEY_K: 41, KEY_L: 42, KEY_M: 43, KEY_N: 44, KEY_O: 45, KEY_P: 46, KEY_Q: 47, KEY_R: 48,
        KEY_S: 49, KEY_T: 50, KEY_U: 51, KEY_V: 52, KEY_W: 53, KEY_X: 54, KEY_Y: 55, KEY_Z: 56,
        F1: 59, F2: 60, F3: 61, F4: 62, F5: 63, F6: 64, F7: 65, F8: 66, F9: 67, F10: 68, F11: 69, F12: 70
    };

    monaco.KeyMod = {
        CtrlCmd: 2048,
        Shift: 1024,
        Alt: 512,
        WinCtrl: 256,
        chord: function(firstPart, secondPart) {
            return ((firstPart & 0x0000FFFF) << 16) | ((secondPart & 0x0000FFFF) >>> 0);
        }
    };

    monaco.languages.CompletionItemKind = {
        Method: 0, Function: 1, Constructor: 2, Field: 3, Variable: 4, Class: 5,
        Struct: 6, Interface: 7, Module: 8, Property: 9, Event: 10, Operator: 11,
        Unit: 12, Value: 13, Constant: 14, Enum: 15, EnumMember: 16, Keyword: 17,
        Text: 18, Color: 19, File: 20, Reference: 21, Customcolor: 22, Folder: 23,
        TypeParameter: 24, User: 25, Issue: 26, Snippet: 27
    };

    monaco.languages.CompletionItemInsertTextRule = {
        None: 0,
        KeepWhitespace: 1,
        InsertAsSnippet: 4
    };

    // ── TextModel Implementation ──────────────────────────────────────────────
    class TextModel {
        constructor(value, languageId, uri) {
            this._value = value || '';
            this._lines = this._value.split(/\r?\n/);
            this._languageId = languageId || 'plaintext';
            this._uri = uri || 'inmemory://model/1';
            this._changeListeners = [];
        }

        getValue() {
            return this._value;
        }

        setValue(newValue) {
            this._value = newValue || '';
            this._lines = this._value.split(/\r?\n/);
            this._emitChange();
        }

        getLanguageId() {
            return this._languageId;
        }

        setLanguageId(newLanguageId) {
            this._languageId = newLanguageId;
        }

        getLineCount() {
            return this._lines.length;
        }

        getLineContent(lineNumber) {
            if (lineNumber < 1 || lineNumber > this._lines.length) return '';
            return this._lines[lineNumber - 1];
        }

        getValueInRange(range) {
            if (!range) return '';
            const startLine = Math.max(1, range.startLineNumber);
            const endLine = Math.min(this._lines.length, range.endLineNumber);
            if (startLine > endLine) return '';

            if (startLine === endLine) {
                const line = this.getLineContent(startLine);
                return line.substring(range.startColumn - 1, range.endColumn - 1);
            }

            const parts = [];
            parts.push(this.getLineContent(startLine).substring(range.startColumn - 1));
            for (let i = startLine + 1; i < endLine; i++) {
                parts.push(this.getLineContent(i));
            }
            parts.push(this.getLineContent(endLine).substring(0, range.endColumn - 1));
            return parts.join('\n');
        }

        getWordAtPosition(position) {
            const line = this.getLineContent(position.lineNumber);
            if (!line) return null;
            const col = position.column - 1;
            const wordRegex = /(-?\d*\.\d\w*)|([^\`\~\!\@\#\$\%\^\&\*\(\)\-\=\+\[\{\]\}\\\|\;\:\'\"\,\.\<\>\/\?\s]+)/g;
            let match;
            while ((match = wordRegex.exec(line)) !== null) {
                const start = match.index;
                const end = start + match[0].length;
                if (col >= start && col <= end) {
                    return {
                        word: match[0],
                        startColumn: start + 1,
                        endColumn: end + 1
                    };
                }
            }
            return null;
        }

        getWordUntilPosition(position) {
            const word = this.getWordAtPosition(position);
            if (!word) return { word: '', startColumn: position.column, endColumn: position.column };
            return {
                word: word.word.substring(0, position.column - word.startColumn),
                startColumn: word.startColumn,
                endColumn: position.column
            };
        }

        getOffsetAtPosition(position) {
            let offset = 0;
            for (let i = 1; i < position.lineNumber; i++) {
                offset += this.getLineContent(i).length + 1; // +1 for newline
            }
            offset += (position.column - 1);
            return offset;
        }

        getPositionAtOffset(offset) {
            let current = 0;
            for (let i = 0; i < this._lines.length; i++) {
                const lineLen = this._lines[i].length + 1;
                if (current + lineLen > offset) {
                    return new Position(i + 1, (offset - current) + 1);
                }
                current += lineLen;
            }
            return new Position(this._lines.length, this._lines[this._lines.length - 1].length + 1);
        }

        applyEdits(edits) {
            if (!Array.isArray(edits) || edits.length === 0) return;
            if (window.KryptonNative && window.KryptonNative.postApplyEdits) {
                window.KryptonNative.postApplyEdits(JSON.stringify(edits));
            }
        }

        onDidChangeContent(callback) {
            this._changeListeners.push(callback);
            return {
                dispose: () => {
                    const idx = this._changeListeners.indexOf(callback);
                    if (idx >= 0) this._changeListeners.splice(idx, 1);
                }
            };
        }

        _emitChange() {
            this._changeListeners.forEach(cb => {
                try { cb(); } catch (e) { console.error('Error in onDidChangeContent:', e); }
            });
        }
    }

    // ── Editor API ────────────────────────────────────────────────────────────
    monaco.editor.createModel = function(value, language, uri) {
        const u = uri ? String(uri) : `inmemory://model/${Date.now()}`;
        const model = new TextModel(value, language, u);
        monaco._models.set(u, model);
        monaco._currentModel = model;
        return model;
    };

    monaco.editor.getModel = function(uri) {
        if (!uri) return monaco._currentModel;
        return monaco._models.get(String(uri)) || null;
    };

    monaco.editor.getModels = function() {
        return Array.from(monaco._models.values());
    };

    monaco.editor.defineTheme = function(themeName, themeData) {
        if (window.KryptonNative && window.KryptonNative.postThemeDefined) {
            window.KryptonNative.postThemeDefined(themeName, JSON.stringify(themeData));
        }
    };

    monaco.editor.setTheme = function(themeName) {
        if (window.KryptonNative && window.KryptonNative.postThemeChanged) {
            window.KryptonNative.postThemeChanged(themeName);
        }
    };

    monaco.editor.addCommand = function(keybinding, handler) {
        const id = 'cmd_' + Math.random().toString(36).substr(2, 9);
        monaco._commands.set(id, handler);
        return id;
    };

    monaco.editor.addAction = function(descriptor) {
        if (!descriptor || !descriptor.id) return;
        monaco._actions.set(descriptor.id, descriptor);
        if (window.KryptonNative && window.KryptonNative.postActionRegistered) {
            window.KryptonNative.postActionRegistered(descriptor.id, descriptor.label || descriptor.id);
        }
    };

    monaco.editor.setModelMarkers = function(model, owner, markers) {
        if (window.KryptonNative && window.KryptonNative.postMarkers) {
            window.KryptonNative.postMarkers(owner || 'default', JSON.stringify(markers || []));
        }
    };

    // ── Languages API ─────────────────────────────────────────────────────────
    monaco.languages.register = function(languageDef) {
        if (languageDef && languageDef.id) {
            console.log(`Registered Monaco language: ${languageDef.id}`);
        }
    };

    monaco.languages.registerCompletionItemProvider = function(languageId, provider) {
        const lang = (languageId || '*').toLowerCase();
        if (!monaco._completionProviders.has(lang)) {
            monaco._completionProviders.set(lang, []);
        }
        monaco._completionProviders.get(lang).push(provider);
        return {
            dispose: () => {
                const list = monaco._completionProviders.get(lang);
                if (list) {
                    const idx = list.indexOf(provider);
                    if (idx >= 0) list.splice(idx, 1);
                }
            }
        };
    };

    monaco.languages.registerHoverProvider = function(languageId, provider) {
        const lang = (languageId || '*').toLowerCase();
        if (!monaco._hoverProviders.has(lang)) {
            monaco._hoverProviders.set(lang, []);
        }
        monaco._hoverProviders.get(lang).push(provider);
    };

    monaco.languages.registerDocumentFormattingEditProvider = function(languageId, provider) {
        const lang = (languageId || '*').toLowerCase();
        if (!monaco._formattingProviders.has(lang)) {
            monaco._formattingProviders.set(lang, []);
        }
        monaco._formattingProviders.get(lang).push(provider);
    };

    monaco.languages.setMonarchTokensProvider = function(languageId, languageDef) {
        if (window.KryptonNative && window.KryptonNative.postMonarchDefined) {
            window.KryptonNative.postMonarchDefined(languageId, JSON.stringify(languageDef));
        }
    };

    // ── Internal Dispatchers (Called by Kotlin via evaluateJavascript) ───────
    monaco._executeCompletionProviders = function(requestId, languageId, line, column, word) {
        const pos = new Position(line, column);
        const model = monaco._currentModel || new TextModel('', languageId, 'temp');
        const lang = (languageId || '').toLowerCase();

        const providers = [];
        if (monaco._completionProviders.has(lang)) {
            providers.push(...monaco._completionProviders.get(lang));
        }
        if (monaco._completionProviders.has('*')) {
            providers.push(...monaco._completionProviders.get('*'));
        }

        if (providers.length === 0) {
            if (window.KryptonNative && window.KryptonNative.postCompletionResult) {
                window.KryptonNative.postCompletionResult(requestId, '[]');
            }
            return;
        }

        const promises = providers.map(p => {
            try {
                const res = p.provideCompletionItems(model, pos, { triggerKind: 0 }, {});
                return Promise.resolve(res);
            } catch (err) {
                console.error('Completion provider error:', err);
                return Promise.resolve(null);
            }
        });

        Promise.all(promises).then(results => {
            const allItems = [];
            results.forEach(res => {
                if (!res) return;
                const items = Array.isArray(res) ? res : (res.suggestions || []);
                items.forEach(it => {
                    allItems.push({
                        label: typeof it.label === 'string' ? it.label : (it.label ? it.label.label : ''),
                        kind: it.kind !== undefined ? it.kind : 1, // Default Function
                        detail: it.detail || '',
                        documentation: typeof it.documentation === 'string' ? it.documentation : (it.documentation ? it.documentation.value : ''),
                        insertText: it.insertText || it.label,
                        isSnippet: (it.insertTextRules && it.insertTextRules === monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet) || false
                    });
                });
            });

            if (window.KryptonNative && window.KryptonNative.postCompletionResult) {
                window.KryptonNative.postCompletionResult(requestId, JSON.stringify(allItems));
            }
        }).catch(err => {
            console.error('Failed to resolve completions:', err);
            if (window.KryptonNative && window.KryptonNative.postCompletionResult) {
                window.KryptonNative.postCompletionResult(requestId, '[]');
            }
        });
    };

    monaco._syncBuffer = function(uri, languageId, content) {
        let model = monaco._models.get(uri);
        if (!model) {
            model = monaco.editor.createModel(content, languageId, uri);
        } else {
            model.setValue(content);
            model.setLanguageId(languageId);
        }
        monaco._currentModel = model;
    };

    // Attach to global window
    window.monaco = monaco;
    console.log('Monaco Compatibility Layer initialized successfully.');
})();
