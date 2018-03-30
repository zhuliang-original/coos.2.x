package top.coos.db.nosql.mongo;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;

import top.coos.core.exceptions.NotInitedException;
import top.coos.db.DbRuntimeException;
import top.coos.log.LogFactory;
import top.coos.setting.Setting;
import top.coos.util.NetUtil;
import top.coos.util.StrUtil;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientOptions.Builder;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * MongoDB工具类
 * 

 *
 */
public class MongoDS implements Closeable {

	private static final Logger log = LogFactory.get();

	/** 默认配置文件 */
	public final static String MONGO_CONFIG_PATH = "config/mongo.setting";

	// MongoDB配置文件
	private Setting setting;
	// MongoDB实例连接列表
	private String[] groups;
	// MongoDB单点连接信息
	private ServerAddress serverAddress;
	// MongoDB客户端对象
	private MongoClient mongo;

	// ---------------------------------------------------------------------------
	// Constructor start
	/**
	 * 构造MongoDB数据源<br>
	 * 调用者必须持有MongoDS实例，否则会被垃圾回收导致写入失败！
	 * 
	 * @param host
	 *            主机（域名或者IP）
	 * @param port
	 *            端口
	 */
	public MongoDS(String host, int port) {

		this.serverAddress = createServerAddress(host, port);
		initSingle();
	}

	/**
	 * 构造MongoDB数据源<br>
	 * 调用者必须持有MongoDS实例，否则会被垃圾回收导致写入失败！
	 * 
	 * @param mongoSetting
	 *            MongoDB的配置文件，如果是null则读取默认配置文件或者使用MongoDB默认客户端配置
	 * @param host
	 *            主机（域名或者IP）
	 * @param port
	 *            端口
	 */
	public MongoDS(Setting mongoSetting, String host, int port) {

		this.setting = mongoSetting;
		this.serverAddress = createServerAddress(host, port);
		initSingle();
	}

	/**
	 * 构造MongoDB数据源<br>
	 * 当提供多个数据源时，这些数据源将为一个副本集或者多个mongos<br>
	 * 调用者必须持有MongoDS实例，否则会被垃圾回收导致写入失败！ 官方文档：
	 * http://docs.mongodb.org/manual/administration/replica-sets/
	 * 
	 * @param groups
	 *            分组列表，当为null或空时使用无分组配置，一个分组使用单一模式，否则使用副本集模式
	 */
	public MongoDS(String... groups) {

		this.groups = groups;
		init();
	}

	/**
	 * 构造MongoDB数据源<br>
	 * 当提供多个数据源时，这些数据源将为一个副本集或者mongos<br>
	 * 调用者必须持有MongoDS实例，否则会被垃圾回收导致写入失败！<br>
	 * 官方文档： http://docs.mongodb.org/manual/administration/replica-sets/
	 * 
	 * @param mongoSetting
	 *            MongoDB的配置文件，必须有
	 * @param groups
	 *            分组列表，当为null或空时使用无分组配置，一个分组使用单一模式，否则使用副本集模式
	 */
	public MongoDS(Setting mongoSetting, String... groups) {

		if (mongoSetting == null) {
			throw new DbRuntimeException("Mongo setting is null!");
		}
		this.setting = mongoSetting;
		this.groups = groups;
		init();
	}

	// ---------------------------------------------------------------------------
	// Constructor end

	/**
	 * 初始化，当给定分组数大于一个时使用
	 */
	public void init() {

		if (groups != null && groups.length > 1) {
			initCloud();
		} else {
			initSingle();
		}
	}

	/**
	 * 初始化<br>
	 * 设定文件中的host和端口有三种形式： --------------------- host = host:port
	 * --------------------- host = host port = port ---------------------
	 * #此种形式使用MongoDB默认端口 host = host ---------------------
	 */
	synchronized public void initSingle() {

		if (setting == null) {
			try {
				setting = new Setting(MONGO_CONFIG_PATH, true);
			} catch (Exception e) {
				// 在single模式下，可以没有配置文件。
			}
		}

		String group = StrUtil.EMPTY;
		if (serverAddress == null) {
			if (groups != null && groups.length == 1) {
				group = groups[0];
			}
			serverAddress = createServerAddress(group);
		}
		try {
			mongo = new MongoClient(serverAddress, buildMongoClientOptions(group));
		} catch (Exception e) {
			throw new DbRuntimeException(StrUtil.format("Init MongoDB pool with connection to [{}] error!", serverAddress),
					e);
		}

		log.info("Init MongoDB pool with connection to [{}]", serverAddress);
	}

	/**
	 * 初始化集群<br>
	 * 集群的其它客户端设定参数使用全局设定<br>
	 * 集群中每一个实例成员用一个group表示，例如：<br>
	 * [db0] host = 10.11.49.157:27117 [db1] host = 10.11.49.157:27118 [db2]
	 * host = 10.11.49.157:27119
	 */
	synchronized public void initCloud() {

		if (groups == null || groups.length == 0) {
			throw new DbRuntimeException("Please give replication set groups!");
		}

		if (setting == null) {
			// 若未指定配置文件，则使用默认配置文件
			setting = new Setting(MONGO_CONFIG_PATH, true);
		}

		List<ServerAddress> addrList = new ArrayList<ServerAddress>();
		for (String group : groups) {
			addrList.add(createServerAddress(group));
		}

		try {
			mongo = new MongoClient(addrList, buildMongoClientOptions(StrUtil.EMPTY));
		} catch (Exception e) {
			log.error("Init MongoDB connection error!", e);
			return;
		}

		log.info("Init MongoDB cloud Set pool with connection to {}", addrList);
	}

	/**
	 * 设定MongoDB配置文件
	 * 
	 * @param setting
	 *            配置文件
	 */
	public void setSetting(Setting setting) {

		this.setting = setting;
	}

	/**
	 * @return 获得MongoDB客户端对象
	 */
	public MongoClient getMongo() {

		return mongo;
	}

	/**
	 * 获得DB
	 * 
	 * @param dbName
	 *            DB
	 * @return DB
	 */
	public MongoDatabase getDb(String dbName) {

		return mongo.getDatabase(dbName);
	}

	/**
	 * 获得MongoDB中指定集合对象
	 * 
	 * @param dbName
	 *            库名
	 * @param collectionName
	 *            集合名
	 * @return DBCollection
	 */
	public MongoCollection<Document> getCollection(String dbName, String collectionName) {

		return getDb(dbName).getCollection(collectionName);
	}

	@Override
	public void close() {

		mongo.close();
	}

	// ---------------------------------------------------------------------------
	// Private method start
	/**
	 * 创建ServerAddress对象，会读取配置文件中的相关信息
	 * 
	 * @param group
	 *            分组，如果为null默认为无分组
	 * @return ServerAddress
	 */
	private ServerAddress createServerAddress(String group) {

		if (setting == null) {
			throw new DbRuntimeException(StrUtil.format(
					"Please indicate setting file or create default [{}], and define group [{}]", MONGO_CONFIG_PATH, group));
		}

		if (group == null) {
			group = StrUtil.EMPTY;
		}

		String tmpHost = setting.getByGroup("host", group);
		if (StrUtil.isBlank(tmpHost)) {
			throw new NotInitedException("Host name is empy of group: " + group);
		}

		final int defaultPort = setting.getInt("port", group, 27017);
		return new ServerAddress(NetUtil.buildInetSocketAddress(tmpHost, defaultPort));
	}

	/**
	 * 创建ServerAddress对象
	 * 
	 * @param host
	 *            主机域名或者IP（如果为空默认127.0.0.1）
	 * @param port
	 *            端口（如果为空默认为）
	 * @return ServerAddress
	 */
	private ServerAddress createServerAddress(String host, int port) {

		return new ServerAddress(host, port);
	}

	/**
	 * 构件MongoDB连接选项<br>
	 * 
	 * @param group
	 *            分组,当分组对应的选项不存在时会读取根选项，如果也不存在使用默认值
	 * @return MongoClientOptions
	 */
	private MongoClientOptions buildMongoClientOptions(String group) {

		return buildMongoClientOptions(MongoClientOptions.builder(), group).build();
	}

	/**
	 * 构件MongoDB连接选项<br>
	 * 
	 * @param group
	 *            分组，当分组对应的选项不存在时会读取根选项，如果也不存在使用默认值
	 * @return Builder
	 */
	private Builder buildMongoClientOptions(Builder builder, String group) {

		if (setting == null) {
			return builder;
		}

		if (group == null) {
			group = StrUtil.EMPTY;
		} else {
			group = group + StrUtil.DOT;
		}

		// 每个主机答应的连接数（每个主机的连接池大小），当连接池被用光时，会被阻塞住
		Integer connectionsPerHost = setting.getInt(group + "connectionsPerHost");
		if (StrUtil.isBlank(group) == false && connectionsPerHost == null) {
			connectionsPerHost = setting.getInt("connectionsPerHost");
		}
		if (connectionsPerHost != null) {
			builder.connectionsPerHost(connectionsPerHost);
			log.debug("MongoDB connectionsPerHost: {}", connectionsPerHost);
		}

		// multiplier for connectionsPerHost for # of threads that can block if
		// connectionsPerHost is 10, and
		// threadsAllowedToBlockForConnectionMultiplier is 5, then 50 threads
		// can block more than that and an exception will be throw --int
		Integer threadsAllowedToBlockForConnectionMultiplier = setting.getInt(group
				+ "threadsAllowedToBlockForConnectionMultiplier");
		if (StrUtil.isBlank(group) == false && threadsAllowedToBlockForConnectionMultiplier == null) {
			threadsAllowedToBlockForConnectionMultiplier = setting.getInt("threadsAllowedToBlockForConnectionMultiplier");
		}
		if (threadsAllowedToBlockForConnectionMultiplier != null) {
			builder.threadsAllowedToBlockForConnectionMultiplier(threadsAllowedToBlockForConnectionMultiplier);
			log.debug("MongoDB threadsAllowedToBlockForConnectionMultiplier: {}",
					threadsAllowedToBlockForConnectionMultiplier);
		}

		// 被阻塞线程从连接池获取连接的最长等待时间（ms） --int
		Integer connectTimeout = setting.getInt(group + "connectTimeout");
		if (StrUtil.isBlank(group) == false && connectTimeout == null) {
			setting.getInt("connectTimeout");
		}
		if (connectTimeout != null) {
			builder.connectTimeout(connectTimeout);
			log.debug("MongoDB connectTimeout: {}", connectTimeout);
		}

		// 套接字超时时间;该值会被传递给Socket.setSoTimeout(int)。默以为0（无穷） --int
		Integer socketTimeout = setting.getInt(group + "socketTimeout");
		if (StrUtil.isBlank(group) == false && socketTimeout == null) {
			setting.getInt("socketTimeout");
		}
		if (socketTimeout != null) {
			builder.socketTimeout(socketTimeout);
			log.debug("MongoDB socketTimeout: {}", socketTimeout);
		}

		return builder;
	}

	// ---------------------------------------------------------------------------
	// Private method end
}