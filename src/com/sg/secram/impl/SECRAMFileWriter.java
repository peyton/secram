/**
 * Copyright © 2013-2016 Swiss Federal Institute of Technology EPFL and Sophia Genetics SA
 * 
 * All rights reserved
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted 
 * provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of 
 * conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of 
 * conditions and the following disclaimer in the documentation and/or other materials provided 
 * with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used 
 * to endorse or promote products derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS 
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY 
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR 
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, 
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER 
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT 
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * PATENTS NOTICE: Sophia Genetics SA holds worldwide pending patent applications in relation with this 
 * software functionality. For more information and licensing conditions, you should contact Sophia Genetics SA 
 * at info@sophiagenetics.com. 
 */
package com.sg.secram.impl;

import htsjdk.samtools.SAMFileHeader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.sg.secram.impl.records.ReadHeader;
import com.sg.secram.impl.records.SecramRecord;
import com.sg.secram.structure.SecramBlock;
import com.sg.secram.structure.SecramCompressionHeaderFactory;
import com.sg.secram.structure.SecramContainer;
import com.sg.secram.structure.SecramContainerFactory;
import com.sg.secram.structure.SecramContainerIO;
import com.sg.secram.structure.SecramHeader;
import com.sg.secram.structure.SecramIO;
import com.sg.secram.util.Timings;

/**
 * Write SECRAM records to disk.
 * @author zhihuang
 *
 */
public class SECRAMFileWriter {

	private File secramFile;
	private int recordsPerContainer = SecramContainer.DEFATUL_RECORDS_PER_CONTAINER;
	private SecramContainerFactory containerFactory;
	private SAMFileHeader samFileHeader;
	private SECRAMSecurityFilter filter;
	private SecramHeader secramHeader;
	private SecramIndex secramIndex;

	private final OutputStream outputStream;
	private long offset;

	private List<SecramRecord> secramRecords = new ArrayList<SecramRecord>();

	/**
	 * Construct the writer by specifying an output file, an original SAM file header, and an encryption key.
	 * @throws IOException
	 */
	public SECRAMFileWriter(final File output, final SAMFileHeader header,
			final byte[] key) throws IOException {
		this.secramFile = output;
		this.outputStream = new BufferedOutputStream(new FileOutputStream(
				output));
		this.samFileHeader = header;
		this.filter = new SECRAMSecurityFilter(key);
		this.containerFactory = new SecramContainerFactory(header,
				recordsPerContainer);
		this.secramIndex = new SecramIndex();

		writeHeader();
	}

	public SAMFileHeader getBAMHeader() {
		return samFileHeader;
	}

	public long getNumberOfWrittenRecords() {
		return this.containerFactory.getGlobalRecordCounter();
	}

	public void close() {
		try {
			if (!secramRecords.isEmpty())
				flushContainer();
			outputStream.flush();
			outputStream.close();

			// Write the index file
			File indexFile = new File(secramFile.getAbsolutePath() + ".secrai");
			secramIndex.writeIndexToFile(indexFile);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public boolean shouldFlushContainer(final SecramRecord nextRecord) {
		return secramRecords.size() >= recordsPerContainer;
	}

	/**
	 * Append a record to the output file.
	 */
	public void appendRecord(SecramRecord record) {
		if (shouldFlushContainer(record)) {
			try {
				flushContainer();
			} catch (IllegalArgumentException | IllegalAccessException
					| IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
		secramRecords.add(record);
	}

	/**
	 * Write a container to the output file.
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws IOException
	 */
	private void flushContainer() throws IllegalArgumentException,
			IllegalAccessException, IOException {
		// encrypt the positions
		long prevOrgPosition = secramRecords.get(0).getAbsolutePosition();
		long prevEncPosition = -1;
		long nanoStart = System.nanoTime();
		for (SecramRecord record : secramRecords) {
			if (record.getAbsolutePosition() - prevOrgPosition != 1) {
				long encPos = filter.encryptPosition(record
						.getAbsolutePosition());
				prevOrgPosition = record.getAbsolutePosition();
				record.setAbsolutionPosition(encPos);
				prevEncPosition = encPos;
			} else {
				record.setAbsolutionPosition(prevEncPosition);
				prevOrgPosition += 1;
			}
			for (ReadHeader rh : record.mReadHeaders) {
				long encNextPos = filter.encryptPosition(rh
						.getNextAbsolutePosition());
				rh.setNextAbsolutionPosition(encNextPos);
			}
		}
		Timings.encryption += System.nanoTime() - nanoStart;

		// process all delta information for relative integer/long encoding
		long prevAbsolutePosition = secramRecords.get(0).getAbsolutePosition();
		int prevCoverage = secramRecords.get(0).mPosCigar.mCoverage;
		int prevQualLen = secramRecords.get(0).mQualityScores.length;
		for (SecramRecord record : secramRecords) {
			record.absolutePositionDelta = record.getAbsolutePosition()
					- prevAbsolutePosition;
			prevAbsolutePosition = record.getAbsolutePosition();
			record.coverageDelta = record.mPosCigar.mCoverage - prevCoverage;
			prevCoverage = record.mPosCigar.mCoverage;
			record.qualityLenDelta = record.mQualityScores.length - prevQualLen;
			prevQualLen = record.mQualityScores.length;
		}

		// initialize the block encryption for this container
		int containerID = containerFactory.getGlobalContainerCounter();
		long containerSalt = 0;
		try {
			SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
			containerSalt = sr.nextLong();
			filter.initContainerEM(containerSalt, containerID);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		nanoStart = System.nanoTime();
		SecramContainer container = containerFactory.buildContainer(
				secramRecords, containerSalt);
		Timings.compression += System.nanoTime() - nanoStart;

		// encrypt the sensitive block (the first external block)
		SecramBlock sensitiveBlock = container.external
				.get(SecramCompressionHeaderFactory.SENSITIVE_FIELD_EXTERNAL_ID);
		nanoStart = System.nanoTime();
		byte[] encBlock = filter.encryptBlock(sensitiveBlock.getRawContent(),
				containerID);
		Timings.encryption += System.nanoTime() - nanoStart;
		sensitiveBlock.setContent(encBlock, encBlock);

		// write out the container, and log the index
		container.offset = offset;
		secramIndex.addTuple(container.absolutePosStart, container.offset);
		offset += SecramContainerIO.writeContainer(container, outputStream);

		secramRecords.clear();
	}

	/**
	 * Write out the SECRAM file header.
	 * @throws IOException
	 */
	private void writeHeader() throws IOException {
		// initialize the order-preserving encryption (ope) for the whole file
		long opeSalt = 0;
		try {
			SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
			opeSalt = sr.nextLong();
			opeSalt = -275065164286408096L;
			filter.initPositionEM(opeSalt);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		secramHeader = new SecramHeader(secramFile.getName(), samFileHeader,
				opeSalt);
		offset = SecramIO.writeSecramHeader(secramHeader, outputStream);
	}
}
