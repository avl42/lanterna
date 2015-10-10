/*
 * This file is part of lanterna (http://code.google.com/p/lanterna/).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2015 Martin
 */
package com.googlecode.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Used to read the input stream character by character and generate {@code Key} objects to be put in the input queue.
 *
 * @author Martin
 */
public class InputDecoder {
    private final Reader source;
    private final List<CharacterPattern> bytePatterns;
    private final List<Character> currentMatching;
    private TerminalPosition lastReportedTerminalPosition;
    private boolean seenEOF;

    /**
     * Creates a new input decoder using a specified Reader as the source to read characters from
     * @param source Reader to read characters from, will be wrapped by a BufferedReader
     */
    public InputDecoder(final Reader source) {
        this.source = new BufferedReader(source);
        this.bytePatterns = new ArrayList<CharacterPattern>();
        this.currentMatching = new ArrayList<Character>();
        this.lastReportedTerminalPosition = null;
        this.seenEOF = false;
    }

    /**
     * Adds another key decoding profile to this InputDecoder, which means all patterns from the profile will be used
     * when decoding input.
     * @param profile Profile to add
     */
    public synchronized void addProfile(KeyDecodingProfile profile) {
        for (CharacterPattern pattern : profile.getPatterns()) {
            //If an equivivalent pattern already exists, remove it first
            bytePatterns.remove(pattern);
            bytePatterns.add(pattern);
        }
    }

    /**
     * Returns a collection of all patterns registered in this InputDecoder.
     * @return Collection of patterns in the InputDecoder
     */
    public synchronized Collection<CharacterPattern> getPatterns() {
        return new ArrayList<CharacterPattern>(bytePatterns);
    }

    /**
     * Removes one pattern from the list of patterns in this InputDecoder
     * @param pattern Pattern to remove
     * @return {@code true} if the supplied pattern was found and was removed, otherwise {@code false}
     */
    public synchronized boolean removePattern(CharacterPattern pattern) {
        return bytePatterns.remove(pattern);
    }

    /**
     * Reads and decodes the next key stroke from the input stream
     * @return Key stroke read from the input stream, or {@code null} if none
     * @throws IOException If there was an I/O error when reading from the input stream
     */
    public synchronized KeyStroke getNextCharacter(boolean blockingIO) throws IOException {

        KeyStroke bestMatch = null;
        int bestLen = 0;
        int curLen = 0;

        while(true) {

            if ( curLen < currentMatching.size() ) {
                // (re-)consume characters previously read:
                curLen++;
            }
            else {
                // If we already have a bestMatch but a chance for a longer match
                //   then we wait a short time for further input:
                if ( ! source.ready() && bestMatch != null) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) { /* ignore */ }
                }
                // if input is available, we can just read a char without waiting,
                // otherwise, for readInput() with no bestMatch found yet,
                //  we have to wait blocking for more input:
                if ( source.ready() || ( blockingIO && bestMatch == null ) ) {
                    int readChar = source.read();
                    if (readChar == -1) {
                        seenEOF = true;
                        if(currentMatching.isEmpty()) {
                            return new KeyStroke(KeyType.EOF);
                        }
                        break;
                    }
                    currentMatching.add( (char)readChar );
                    curLen++;
                } else { // no more available input at this time.
                    // already found something:
                    if (bestMatch != null) {
                        break; // it's something...
                    }
                    // otherwise: no KeyStroke yet
                    return null;
                }
            }

            List<Character> curSub = currentMatching.subList(0, curLen);
            Matching matching = getBestMatch( curSub );

            // fullMatch found...
            if (matching.fullMatch != null) {
                bestMatch = matching.fullMatch;
                bestLen = curLen;

                if (! matching.partialMatch) {
                    // that match and no more
                    break;
                } else {
                    // that match, but maybe more
                    continue;
                }
            }
            // No match found yet, but there's still potential...
            else if ( matching.partialMatch ) {
                continue;
            }
            // no longer match possible at this point:
            else {
                if (bestMatch != null ) {
                    // there was already a previous full-match, use it:
                    break;
                } else { // invalid input!
                    // remove the whole fail and re-try finding a KeyStroke...
                    curSub.clear(); // or just 1 char?  currentMatching.remove(0);
                    curLen = 0;
                    continue;
                }
            }
        }

        //Did we find anything? Otherwise return null
        if(bestMatch == null) {
            if(seenEOF) {
                currentMatching.clear();
                return new KeyStroke(KeyType.EOF);
            }
            return null;
        }

        List<Character> bestSub = currentMatching.subList(0, bestLen );

        if (bestMatch.getKeyType() == KeyType.CursorLocation) {
            TerminalPosition cursorPosition = ScreenInfoCharacterPattern.getCursorPosition(bestSub);
            if(cursorPosition != null && cursorPosition.getColumn() == 5 && cursorPosition.getRow() == 1) {
                //Special case for CTRL + F3
                bestMatch = new KeyStroke(KeyType.F3, true, false);
            }
            else {
                lastReportedTerminalPosition = cursorPosition;
            }
        }

        bestSub.clear(); // remove matched characters from input
        return bestMatch;
    }

    /**
     * Returns the last position the cursor was reported by the terminal to be at, after a user-triggered cursor report
     * @return Position of the cursor, as declared by the last cursor report this InputDecoder has seen
     */
    public TerminalPosition getLastReportedTerminalPosition() {
        return lastReportedTerminalPosition;
    }

    private synchronized Matching getBestMatch(List<Character> characterSequence) {
        boolean partialMatch = false;
        KeyStroke bestMatch = null;
        for(CharacterPattern pattern : bytePatterns) {
            if (pattern.matches(characterSequence)) {
                if (pattern.isCompleteMatch(characterSequence)) {
                    bestMatch = pattern.getResult(characterSequence);
                } else {
                    partialMatch = true;
                }
            }
        }
        return new Matching(partialMatch, bestMatch);
    }

    private static class Matching {
        final boolean partialMatch;
        final KeyStroke fullMatch;

        public Matching(boolean partialMatch, KeyStroke fullMatch) {
            this.partialMatch = partialMatch;
            this.fullMatch = fullMatch;
        }

        @Override
        public String toString() {
            return "Matching{" + "partialMatch=" + partialMatch + ", fullMatch=" + fullMatch + '}';
        }
    }
}
