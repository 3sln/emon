import { createTranslator, ObjectCatalogue, ChainCatalogue } from '@3sln/bab';
const { locale = 'en', messages = {}, parent = {}, onMissing } = globalThis.__site ?? {};
export const tr = createTranslator({ locale, catalogue: new ChainCatalogue(new ObjectCatalogue(messages), new ObjectCatalogue(parent)), onMissing });
