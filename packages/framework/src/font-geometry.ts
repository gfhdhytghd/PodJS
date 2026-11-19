import {createSignal,onCleanup} from "solid-js";
import {textLayout} from "./services.ts";
import {getDisplayMetrics,getFontMetrics,hasCapability,onLifecycleChange} from "./watch.ts";

/** A measurement cache can share this revision across all its rows. A resumed
 * host may have a different atlas, density or platform font-scale setting. */
export function createFontGeometryRevision(slots: readonly number[]): () => number {
  const [revision, setRevision] = createSignal(0);
  const atlas = () => JSON.stringify([getDisplayMetrics(), slots.map(getFontMetrics)]);
  let previousAtlas = atlas(), platform: string | undefined;
  let request = 0;
  const refresh = () => {
    const current = ++request;
    const nextAtlas = atlas();
    const publish = (nextPlatform = platform) => {
      if (current !== request) return;
      const changed = previousAtlas !== nextAtlas || (platform !== undefined && platform !== nextPlatform);
      previousAtlas = nextAtlas;
      platform = nextPlatform;
      if (changed) setRevision(value => value + 1);
    };
    if (!hasCapability("text.environment")) { publish(); return; }
    void textLayout.environment().then(
      ({ density, fontScale }) => publish(JSON.stringify([density, fontScale])),
      () => publish(),
    );
  };
  const remove = onLifecycleChange(state => { if (state === "active") refresh(); });
  refresh();
  onCleanup(() => { request++; remove(); });
  return revision;
}
