package me.TreeOfSelf.PandaOcclusion;

import java.util.concurrent.atomic.AtomicLong;

public interface SectionVisHolder {
	AtomicLong panda$vis();

	long[] panda$faces();

	void panda$setFaces(long[] faces);

	long[] panda$opaque();

	void panda$setOpaque(long[] opaque);

	byte panda$mayPlay();

	void panda$setMayPlay(byte mayPlay);

	long[] panda$soundBits();

	void panda$setSoundBits(long[] bits);

	java.lang.ref.SoftReference<SectionGroups> panda$groups();

	void panda$setGroups(java.lang.ref.SoftReference<SectionGroups> groups);

	long[] panda$oreBits();

	void panda$setOreBits(long[] bits);
}
