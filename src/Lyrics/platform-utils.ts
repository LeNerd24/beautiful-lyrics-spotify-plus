import { transferableAbortController } from 'node:util';

export const createAbortController = () => transferableAbortController();
export const cloneLyrics = <T>(value: T): T => JSON.parse(JSON.stringify(value));
