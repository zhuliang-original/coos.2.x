package top.coos.server.web.listener;

import java.io.RandomAccessFile;

import org.slf4j.Logger;

import top.coos.core.io.IoUtil;
import top.coos.logger.LogFactory;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;

/**
 * 文件进度指示监听
 * 
 *
 */
public class FileProgressiveFutureListener implements ChannelProgressiveFutureListener {

	private static final Logger log = LogFactory.get();

	private RandomAccessFile raf;

	public FileProgressiveFutureListener(RandomAccessFile raf) {

		this.raf = raf;
	}

	@Override
	public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {

		log.debug("Transfer progress: {} / {}", progress, total);
	}

	@Override
	public void operationComplete(ChannelProgressiveFuture future) {

		IoUtil.close(raf);
		log.debug("Transfer complete.");
	}

	/**
	 * 构建文件进度指示监听
	 * 
	 * @param raf
	 *            RandomAccessFile
	 * @return 文件进度指示监听
	 */
	public static FileProgressiveFutureListener build(RandomAccessFile raf) {

		return new FileProgressiveFutureListener(raf);
	}
}
