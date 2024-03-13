package cartoland.utilities;

import cartoland.Cartoland;
import cartoland.commands.AdminCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * {@code TimerHandle} is a utility class that handles schedule. Including checking if functions should execute in every
 * hour or handle {@code /admin temp_ban} with scheduled service. Can not be instantiated or inherited.
 *
 * @since 2.1
 * @author Alex Cai
 */
public final class TimerHandle
{
	private TimerHandle()
	{
		throw new AssertionError(IDs.YOU_SHALL_NOT_ACCESS);
	}

	public record TimerEvent(int hour, Runnable function) {}

	private static final short DAYS = 366; //一年有366天
	private static final short MONTHS = 12; //一年有12月
	public static final short HOURS = 24; //一天有24小時

	private static final String BIRTHDAY_MAP = "serialize/birthday_map.ser";
	private static final String SCHEDULED_EVENTS = "serialize/scheduled_events.ser";

	@SuppressWarnings("unchecked") //閉嘴IntelliJ IDEA
	private static final Map<Long, Short> birthdayMap = CastToInstance.modifiableMap(FileHandle.deserialize(BIRTHDAY_MAP));
	@SuppressWarnings("unchecked") //閉嘴IntelliJ IDEA
	private static final Set<Long>[] birthdayArray = new HashSet[DAYS];

	@SuppressWarnings({"unchecked"}) //閉嘴IntelliJ IDEA
	private static final Set<Runnable>[] hourRunFunctions = new HashSet[HOURS];
	@SuppressWarnings("unchecked") //閉嘴IntelliJ IDEA
	private static final Map<String, TimerEvent> scheduledEvents = CastToInstance.modifiableMap(FileHandle.deserialize(SCHEDULED_EVENTS)); //timer event是匿名的 scheduled event是有名字的
	private static final Set<TimerEvent> toBeRemoved = new HashSet<>(); //不能直接在Runnable裡呼叫unregister

	static
	{
		FileHandle.registerSerialize(BIRTHDAY_MAP, birthdayMap);
		FileHandle.registerSerialize(SCHEDULED_EVENTS, scheduledEvents);

		//生日
		for (short i = 0; i < DAYS; i++)
			birthdayArray[i] = new HashSet<>();
		for (Map.Entry<Long, Short> idAndBirthday : birthdayMap.entrySet())
			birthdayArray[idAndBirthday.getValue() - 1].add(idAndBirthday.getKey());

		//初始化時間事件
		for (short i = 0; i < HOURS; i++)
			hourRunFunctions[i] = new HashSet<>();

		//半夜12點
		TimerHandle.registerTimerEvent(new TimerEvent(0, FileHandle::flushLog)); //更換log的日期
		TimerHandle.registerTimerEvent(new TimerEvent(0, () -> //和生日有關的
		{
			LocalDate today = LocalDate.now();
			Set<Long> birthdayMembersID = birthdayArray[getDateOfYear(today.getMonthValue(), today.getDayOfMonth()) - 1]; //今天生日的成員們的ID
			if (birthdayMembersID.isEmpty()) //今天沒有人生日
				return;
			TextChannel lobbyChannel = Cartoland.getJDA().getTextChannelById(IDs.LOBBY_CHANNEL_ID); //大廳頻道
			if (lobbyChannel == null) //找不到大廳頻道
				return;
			for (long birthdayMemberID : birthdayMembersID)
				lobbyChannel.sendMessage("今天是 <@" + Long.toUnsignedString(birthdayMemberID) + "> 的生日！").queue();
		}));

		//凌晨3點
		TimerHandle.registerTimerEvent(new TimerEvent(3, () -> //好棒 三點了
		{
			TextChannel undergroundChannel = Cartoland.getJDA().getTextChannelById(IDs.UNDERGROUND_CHANNEL_ID);
			if (undergroundChannel == null) //找不到地下頻道
				return; //結束
			undergroundChannel.sendMessage("https://i.imgur.com/c0HCirP.jpg").queue(); //誰會想在凌晨三點吃美味蟹堡
			undergroundChannel.sendMessage("https://i.imgur.com/EGO35hf.jpg").queue(); //好棒，三點了
		}));

		//中午12點
		TimerHandle.registerTimerEvent(new TimerEvent(12, () -> //中午十二點時處理並提醒未解決的論壇貼文
		{
			ForumChannel questionsChannel = Cartoland.getJDA().getForumChannelById(IDs.QUESTIONS_CHANNEL_ID); //疑難雜症頻道
			if (questionsChannel == null)
				return; //找不到就算了
			for (ThreadChannel forumPost : questionsChannel.getThreadChannels()) //走訪論壇貼文們
				ForumsHandle.tryIdleQuestionForumPost(forumPost); //試著讓它們idle
		}));
	}

	//https://stackoverflow.com/questions/65984126
	private static final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
	private static int nowHour = LocalTime.now().getHour(); //現在是幾點
	private static long hoursFrom1970 = System.currentTimeMillis() / (1000 * 60 * 60); //從1970年1月1日開始過了幾個小時
	private static final ScheduledFuture<?> everyHour = executorService.scheduleAtFixedRate(() -> //每小時執行一次
	{
		hoursFrom1970++; //增加小時
		nowHour++; //增加現在幾點
		if (nowHour == HOURS) //第24小時是0點
			nowHour = 0;

		if (!toBeRemoved.isEmpty()) //有事件要被移除
		{
			for (TimerEvent removeTimerEvent : toBeRemoved) //移除要被移除的事件們
				hourRunFunctions[removeTimerEvent.hour].remove(removeTimerEvent.function);
			toBeRemoved.clear();
		}

		for (Runnable event : hourRunFunctions[nowHour]) //走訪被註冊的事件們
			event.run(); //執行

		unbanMembers(); //時間到了的話就解除封鎖
	}, secondsUntil((nowHour + 1) % HOURS), 60 * 60, TimeUnit.SECONDS); //從下個小時開始

	public static long getHoursFrom1970()
	{
		return hoursFrom1970;
	}

	/**
	 * Get date of year (start with 1). This method always assume the year is a leap year, hence February has
	 * 29 days. The value range of the method is from 1 to 366.
	 *
	 * @param month The month
	 * @param date The day of the month
	 * @return The day of year. Range: 1 ~ 366
	 * @since 2.1
	 * @author Alex Cai
	 */
	private static short getDateOfYear(int month, int date)
	{
		return (short) (getDaysReachMonth(month) + date);
	}

	private static short getDaysReachMonth(int month)
	{
		return (short) switch (month) //需要幾天才會抵達這個月 從0開始
		{
			case 1 -> 0;
			case 2 -> 31;
			case 3 -> 31 + 29;
			case 4 -> 31 + 29 + 31;
			case 5 -> 31 + 29 + 31 + 30;
			case 6 -> 31 + 29 + 31 + 30 + 31;
			case 7 -> 31 + 29 + 31 + 30 + 31 + 30;
			case 8 -> 31 + 29 + 31 + 30 + 31 + 30 + 31;
			case 9 -> 31 + 29 + 31 + 30 + 31 + 30 + 31 + 31;
			case 10 -> 31 + 29 + 31 + 30 + 31 + 30 + 31 + 31 + 30;
			case 11 -> 31 + 29 + 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31;
			case 12 -> 31 + 29 + 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 + 30;
			default -> throw new IllegalArgumentException("Month must between 1 and 12!");
		};
	}

	private static long secondsUntil(int hour)
	{
		if (hour < 0 || hour > 23)
			throw new IllegalArgumentException("Hour must between 0 and 23!");
		LocalDateTime now = LocalDateTime.now(); //現在的時間
		LocalDateTime untilTime, targetTime = now.withHour(hour).withMinute(0).withSecond(0); //目標時間

		if (now.isAfter(targetTime)) //如果現在的小時已經超過了目標的小時 例如要在3點時執行 但現在的時間已經4點了
			untilTime = targetTime.plusDays(1L); //明天再執行
		else
			untilTime = targetTime;

		return Duration.between(now, untilTime).getSeconds();
	}

	private static void unbanMembers()
	{
		//根據現在的時間 決定是否解ban
		Set<long[]> tempBanSet = AdminCommand.tempBanSet;
		if (tempBanSet.isEmpty()) //沒有人被temp_ban
			return; //不用執行
		//這以下是有關解ban的程式碼
		Set<long[]> bannedMembers = new HashSet<>(tempBanSet); //建立新物件 以免修改到原set
		JDA jda = Cartoland.getJDA();
		for (long[] bannedMember : bannedMembers)
		{
			if (hoursFrom1970 < bannedMember[AdminCommand.BANNED_TIME]) //還沒到這個人要被解ban的時間
				continue; //下面一位
			Guild bannedServer = jda.getGuildById(bannedMember[AdminCommand.BANNED_SERVER]); //找到當初ban他的群組
			if (bannedServer != null) //群組還在
				jda.retrieveUserById(bannedMember[AdminCommand.USER_ID_INDEX]) //找到這名使用者後解ban他
						.queue(user -> bannedServer.unban(user).queue()); //解ban
			AdminCommand.tempBanSet.remove(bannedMember); //不再紀錄這名使用者
		}
	}

	private static void registerTimerEvent(TimerEvent timerEvent)
	{
		hourRunFunctions[timerEvent.hour].add(timerEvent.function);
	}

	public static void registerScheduledEvent(String name, TimerEvent timerEvent)
	{
		registerTimerEvent(timerEvent);
		scheduledEvents.put(name, timerEvent);
	}

	public static boolean hasScheduledEvent(String name)
	{
		return scheduledEvents.containsKey(name);
	}

	public static Set<String> scheduledEventsNames()
	{
		return scheduledEvents.keySet();
	}

	public static void unregisterTimerEvent(TimerEvent timerEvent)
	{
		toBeRemoved.add(timerEvent);
	}

	public static void unregisterScheduledEvent(String name)
	{
		unregisterTimerEvent(scheduledEvents.remove(name));
	}

	/**
	 * Stop the {@link #everyHour} timer. This method will be called in
	 * {@link cartoland.events.BotOnlineOffline#onShutdown} when the bot went offline.
	 *
	 * @since 2.1
	 * @author Alex Cai
	 */
	public static void stopTimer()
	{
		//https://stackoverflow.com/questions/34202701
		everyHour.cancel(true);
		executorService.shutdown();
	}

	static String getTimeString()
	{
		LocalTime now = LocalTime.now(); //現在
		return String.format("%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());
	}

	public static String getDateString()
	{
		return LocalDate.now().toString();
	}

	public static void setBirthday(long userID, int month, int date)
	{
		Short oldBirthday = birthdayMap.get(userID); //獲取舊生日
		if (oldBirthday != null) //如果確實設定過舊生日
			birthdayArray[oldBirthday - 1].remove(userID); //移除設定
		short dateOfYear = getDateOfYear(month, date); //一年中的第幾天 1月1號為1 12月31號為366
		birthdayArray[dateOfYear - 1].add(userID); //將該使用者增加到那天生日的清單中
		birthdayMap.put(userID, dateOfYear); //設定使用者的生日
	}

	public static short[] getBirthday(long userID)
	{
		Short birthdayBox = birthdayMap.get(userID); //查詢map的紀錄
		if (birthdayBox == null)
			return null;
		short birthday = birthdayBox; //解包
		short daysReachLastMonth = 0;
		//舉例 生日在2月10號, birthday = 31(1月的天數) + 10 = 41
		//第一次迴圈(month = 2), daysReachThisMonth = 31(1月的天數), 31 < 41, 不通過
		//第二次迴圈(month = 3), daysReachThisMonth = 31 + 29, 60 >= 41, 因此return {3 - 1, 41 - 31}
		for (short month = 2, daysReachThisMonth; month <= MONTHS; month++) //從2月開始 一路到12月
		{
			daysReachThisMonth = getDaysReachMonth(month);
			if (daysReachThisMonth >= birthday) //總共的天數 - 抵達這個月需要的天數 >= 生日
				return new short[]{(short) (month - 1), (short) (birthday - daysReachLastMonth)};
			daysReachLastMonth = daysReachThisMonth;
		}
		return new short[]{MONTHS, (short) (birthday - daysReachLastMonth) };
	}

	public static void deleteBirthday(long memberID)
	{
		Short oldBirthday = birthdayMap.remove(memberID); //移除舊生日 並把移除掉的值存起來
		if (oldBirthday != null) //如果設定過舊生日
			birthdayArray[oldBirthday - 1].remove(memberID); //從記錄中移除這位成員
	}
}